/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.analyzer.managers

import ch.frankel.slf4k.*

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

import com.here.ort.analyzer.Main
import com.here.ort.analyzer.PackageManager
import com.here.ort.analyzer.PackageManagerFactory
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.HashAlgorithm
import com.here.ort.model.Identifier
import com.here.ort.model.Package
import com.here.ort.model.PackageReference
import com.here.ort.model.Project
import com.here.ort.model.ProjectAnalyzerResult
import com.here.ort.model.RemoteArtifact
import com.here.ort.model.Scope
import com.here.ort.model.VcsInfo
import com.here.ort.model.jsonMapper
import com.here.ort.utils.OS
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.asTextOrEmpty
import com.here.ort.utils.collectMessages
import com.here.ort.utils.log
import com.here.ort.utils.safeDeleteRecursively
import com.here.ort.utils.showStackTrace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.SortedSet

const val COMPOSER_BINARY = "composer.phar"
const val COMPOSER_GLOBAL_SCRIPT_FILE_NAME = "composer"
const val COMPOSER_GLOBAL_SCRIPT_FILE_NAME_WINDOWS = "composer.bat"
const val COMPOSER_LOCK_FILE_NAME = "composer.lock"

class PhpComposer : PackageManager() {
    companion object : PackageManagerFactory<PhpComposer>(
            "https://getcomposer.org/",
            "PHP",
            listOf("composer.json")
    ) {
        override fun create() = PhpComposer()
    }

    override fun command(workingDir: File) =
            if (File(workingDir, COMPOSER_BINARY).isFile) {
                "php $COMPOSER_BINARY"
            } else {
                if (OS.isWindows) {
                    COMPOSER_GLOBAL_SCRIPT_FILE_NAME_WINDOWS
                } else {
                    COMPOSER_GLOBAL_SCRIPT_FILE_NAME
                }
            }

    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        val workingDir = definitionFile.parentFile
        val vendorDir = File(workingDir, "vendor")
        var tempVendorDir: File? = null

        try {
            if (vendorDir.isDirectory) {
                val tempDir = createTempDir(Main.TOOL_NAME, ".tmp", workingDir)
                tempVendorDir = File(tempDir, "composer_vendor")
                log.warn { "'$vendorDir' already exists, temporarily moving it to '$tempVendorDir'." }
                Files.move(vendorDir.toPath(), tempVendorDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }

            installDependencies(workingDir)

            log.info { "Reading $COMPOSER_LOCK_FILE_NAME file in ${workingDir.absolutePath}..." }
            val lockFile = jsonMapper.readTree(File(workingDir, COMPOSER_LOCK_FILE_NAME))
            val packages = parseInstalledPackages(lockFile)

            log.info { "Reading ${definitionFile.name} file in ${workingDir.absolutePath}..." }
            val manifest = jsonMapper.readTree(definitionFile)

            val scopes = sortedSetOf(
                    parseScope("require", true, manifest, lockFile, packages),
                    parseScope("require-dev", false, manifest, lockFile, packages)
            )

            val project = parseProject(definitionFile, scopes)

            return ProjectAnalyzerResult(Main.allowDynamicVersions, project,
                    packages.values.map { it.toCuratedPackage() }.toSortedSet())
        } finally {
            // Delete vendor folder to not pollute the scan.
            log.info { "Deleting temporary '$vendorDir'..." }
            vendorDir.safeDeleteRecursively()

            // Restore any previously existing "vendor" directory.
            if (tempVendorDir != null) {
                log.info { "Restoring original '$vendorDir' directory from '$tempVendorDir'." }
                Files.move(tempVendorDir.toPath(), vendorDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (!tempVendorDir.parentFile.delete()) {
                    throw IOException("Unable to delete the '${tempVendorDir.parent}' directory.")
                }
            }
        }
    }

    private fun parseScope(scopeName: String, delivered: Boolean, manifest: JsonNode, lockFile: JsonNode,
                           packages: Map<String, Package>): Scope {
        val requiredPackages = manifest[scopeName]?.fieldNames() ?: listOf<String>().iterator()
        val dependencies = buildDependencyTree(requiredPackages, lockFile, packages)
        return Scope(scopeName, delivered, dependencies)
    }

    private fun buildDependencyTree(dependencies: Iterator<String>, lockFile: JsonNode,
                                    packages: Map<String, Package>): SortedSet<PackageReference> {
        val packageReferences = mutableSetOf<PackageReference>()

        dependencies.forEach { packageName ->
            // Composer allows declaring the required PHP version including any extensions, such as "ext-curl". Language
            // implementations are not included in the results from other analyzer modules, so we want to skip them here
            // as well.
            if (packageName != "php" && !packageName.startsWith("ext-")) {
                val packageInfo = packages[packageName]
                        ?: throw IOException("Could not find package info for $packageName")
                try {
                    val transitiveDependencies = getRuntimeDependencies(packageName, lockFile)
                    packageReferences.add(packageInfo.toReference(
                            buildDependencyTree(transitiveDependencies, lockFile, packages)))
                } catch (e: Exception) {
                    e.showStackTrace()
                    PackageReference(packageInfo.id, sortedSetOf<PackageReference>(), e.collectMessages())
                }
            }
        }
        return packageReferences.toSortedSet()
    }

    private fun parseProject(definitionFile: File, scopes: SortedSet<Scope>): Project {
        val json = jsonMapper.readTree(definitionFile)
        val homepageUrl = json["homepage"].asTextOrEmpty()
        val vcs = parseVcsInfo(json)

        return Project(
                id = Identifier(
                        provider = PhpComposer.toString(),
                        namespace = "",
                        name = json["name"].asText(),
                        version = json["version"].asTextOrEmpty()
                ),
                declaredLicenses = parseDeclaredLicenses(json),
                definitionFilePath = VersionControlSystem.getPathToRoot(definitionFile) ?: "",
                aliases = emptyList(),
                vcs = vcs,
                vcsProcessed = processProjectVcs(definitionFile.parentFile, vcs, homepageUrl),
                homepageUrl = homepageUrl,
                scopes = scopes
        )
    }

    private fun parseInstalledPackages(json: JsonNode): Map<String, Package> {
        val packages = mutableMapOf<String, Package>()

        listOf("packages", "packages-dev").forEach {
            json[it]?.forEach { pkgInfo ->
                val name = pkgInfo["name"].asText()
                val version = pkgInfo["version"].asTextOrEmpty()
                val homepageUrl = pkgInfo["homepage"].asTextOrEmpty()
                val vcsFromPackage = parseVcsInfo(pkgInfo)

                // I could not find documentation on the schema of composer.lock, but there is a schema for
                // composer.json at https://getcomposer.org/schema.json and it does not include the "version" field in
                // the list of required properties.
                if (version.isEmpty()) {
                    log.warn { "No version information found for package $name." }
                }

                packages[name] = Package(
                        id = Identifier(
                                provider = PhpComposer.toString(),
                                namespace = "",
                                name = name,
                                version = version
                        ),
                        declaredLicenses = parseDeclaredLicenses(pkgInfo),
                        description = pkgInfo["description"].asTextOrEmpty(),
                        homepageUrl = homepageUrl,
                        binaryArtifact = parseBinaryArtifact(pkgInfo),
                        sourceArtifact = RemoteArtifact.EMPTY,
                        vcs = vcsFromPackage,
                        vcsProcessed = processPackageVcs(vcsFromPackage, homepageUrl)
                )
            }
        }
        return packages
    }

    private fun parseDeclaredLicenses(packageInfo: JsonNode) =
            packageInfo["license"]?.mapNotNull { it?.asText() }?.toSortedSet() ?: sortedSetOf<String>()

    private fun parseVcsInfo(packageInfo: JsonNode): VcsInfo {
        return packageInfo["source"]?.let {
            VcsInfo(it["type"].asTextOrEmpty(), it["url"].asTextOrEmpty(), it["reference"].asTextOrEmpty())
        } ?: VcsInfo.EMPTY
    }

    private fun parseBinaryArtifact(packageInfo: JsonNode): RemoteArtifact {
        return packageInfo["dist"]?.let {
            val sha = it["shasum"].asTextOrEmpty()
            // "shasum" is SHA-1: https://github.com/composer/composer/blob/ \
            // 285ff274accb24f45ffb070c2b9cfc0722c31af4/src/Composer/Repository/ArtifactRepository.php#L149
            val algo = if (sha.isEmpty()) HashAlgorithm.UNKNOWN else HashAlgorithm.SHA1
            RemoteArtifact(it["url"].asTextOrEmpty(), sha, algo)
        } ?: RemoteArtifact.EMPTY
    }

    private fun getRuntimeDependencies(packageName: String, lockFile: JsonNode): Iterator<String> {
        listOf("packages", "packages-dev").forEach {
            lockFile[it]?.forEach { packageInfo ->
                if (packageInfo["name"].asTextOrEmpty() == packageName) {
                    val requiredPackages = packageInfo["require"]
                    if (requiredPackages != null && requiredPackages.isObject) {
                        return (requiredPackages as ObjectNode).fieldNames()
                    }
                }
            }
        }

        return emptyList<String>().iterator()
    }

    private fun installDependencies(workingDir: File) {
        require(Main.allowDynamicVersions || File(workingDir, COMPOSER_LOCK_FILE_NAME).isFile) {
            "No lock file found in $workingDir, dependency versions are unstable."
        }

        ProcessCapture(workingDir, *command(workingDir).split(" ").toTypedArray(), "install")
                .requireSuccess()
    }
}