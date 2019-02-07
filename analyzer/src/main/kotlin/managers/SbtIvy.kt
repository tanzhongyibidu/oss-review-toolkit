/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule

import com.here.ort.analyzer.AbstractPackageManagerFactory
import com.here.ort.analyzer.PackageManager
import com.here.ort.analyzer.ResolutionResult
import com.here.ort.model.Identifier
import com.here.ort.model.PackageReference
import com.here.ort.model.ProjectAnalyzerResult
import com.here.ort.model.Scope
import com.here.ort.model.config.AnalyzerConfiguration
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.model.xmlMapper
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.OS
import com.here.ort.utils.getCommonFilePrefix
import com.here.ort.utils.getUserHomeDirectory
import com.here.ort.utils.log
import com.here.ort.utils.suppressInput

import java.io.File

import kotlin.collections.LinkedHashMap

/**
 * The sbt package manager for Scala, see https://www.scala-sbt.org/.
 */
class SbtIvy(name: String, analyzerConfig: AnalyzerConfiguration, repoConfig: RepositoryConfiguration) :
        PackageManager(name, analyzerConfig, repoConfig), CommandLineTool {
    companion object {
        private val MAJOR_VERSIONS = listOf("0.13", "1.0")

        private const val DEPENDENCY_GRAPH_PLUGIN_NAME = "sbt-dependency-graph"
        private const val DEPENDENCY_GRAPH_PLUGIN_VERSION = "0.9.2"
        private const val DEPENDENCY_GRAPH_PLUGIN_DECLARATION = "addSbtPlugin(" +
                "\"net.virtual-void\" % \"$DEPENDENCY_GRAPH_PLUGIN_NAME\" % \"$DEPENDENCY_GRAPH_PLUGIN_VERSION\"" +
                ")"

        // Batch mode (which suppresses interactive prompts) is only supported on non-Windows, see
        // https://github.com/sbt/sbt-launcher-package/blob/d251388/src/universal/bin/sbt#L86.
        private val BATCH_MODE = if (!OS.isWindows) "-batch" else ""

        // See https://github.com/sbt/sbt/issues/2695.
        private val LOG_NO_FORMAT = "-Dsbt.log.noformat=true".let {
            if (OS.isWindows) {
                "\"$it\""
            } else {
                it
            }
        }

        // In the output of "sbt projects" the current project is indicated by an asterisk.
        private val PROJECT_REGEX = Regex("\\[info] \t ([ *]) (.+)")

        private const val IVY_REPORT_FILE_NAME = "ivy-report.xsl"
    }

    class Factory : AbstractPackageManagerFactory<SbtIvy>("SbtIvy") {
        override val globsForDefinitionFiles = listOf("build.sbt", "build.scala")

        override fun create(analyzerConfig: AnalyzerConfiguration, repoConfig: RepositoryConfiguration) =
                SbtIvy(managerName, analyzerConfig, repoConfig)
    }

    private object DeduplicatingUntypedObjectDeserializer : UntypedObjectDeserializer(null, null) {
        // Work-around https://github.com/FasterXML/jackson-dataformat-xml/issues/205.
        override fun mapObject(p: JsonParser, ctxt: DeserializationContext): Any {
            fun <K, V> getNextKeySuffixNumber(map: Map<K, V>, key: String): Int {
                val keyPrefix = "$key-"

                val largestSuffix = map.keys.mapNotNull {
                    if (it is String && it.startsWith(keyPrefix)) it.removePrefix(keyPrefix).toIntOrNull() else null
                }.max() ?: 0

                return largestSuffix + 1
            }

            // The code below is a Kotlin version of the super implementation, except for the commented code to
            // de-duplicate keys.
            val key1 = when (val token = p.currentToken) {
                JsonToken.START_OBJECT -> p.nextFieldName()
                JsonToken.FIELD_NAME -> p.currentName
                else -> {
                    if (token != JsonToken.END_OBJECT) {
                        return ctxt.handleUnexpectedToken(handledType(), p)
                    }
                    null
                }
            } ?: return LinkedHashMap<String, Any>(2)

            p.nextToken()
            val value1 = deserialize(p, ctxt)

            val key2 = p.nextFieldName()
            if (key2 == null) {
                val result = LinkedHashMap<String, Any>(2)
                result[key1] = value1
                return result
            }

            p.nextToken()
            val value2 = deserialize(p, ctxt)

            var key = p.nextFieldName()

            if (key == null) {
                val result = LinkedHashMap<String, Any>(4)
                result[key1] = value1

                // De-duplicate the key.
                val dupKey2 = if (result.contains(key2)) {
                    val suffix = getNextKeySuffixNumber(result, key2)
                    "$key2-$suffix"
                } else {
                    key2
                }
                result[dupKey2] = value2

                return result
            }

            val result = LinkedHashMap<String, Any>()
            result[key1] = value1

            // De-duplicate the key.
            val dupKey2 = if (result.contains(key2)) {
                val suffix = getNextKeySuffixNumber(result, key2)
                "$key2-$suffix"
            } else {
                key2
            }
            result[dupKey2] = value2

            do {
                p.nextToken()

                // De-duplicate the key.
                val dupKey = if (result.contains(key)) {
                    val suffix = getNextKeySuffixNumber(result, key)
                    "$key-$suffix"
                } else {
                    key
                }
                result[dupKey] = deserialize(p, ctxt)

                key = p.nextFieldName()
            } while (key != null)

            return result
        }
    }

    private data class SbtProject(val projectName: String, val definitionFile: File, val ivyReportFile: File)

    private var pluginDeclarationFiles = emptyList<File>()

    override fun command(workingDir: File?) = if (OS.isWindows) "sbt.bat" else "sbt"

    override fun beforeResolution(definitionFiles: List<File>) {
        log.info { "Adding $DEPENDENCY_GRAPH_PLUGIN_NAME plugin version $DEPENDENCY_GRAPH_PLUGIN_VERSION..." }

        val home = getUserHomeDirectory()
        pluginDeclarationFiles = MAJOR_VERSIONS.map {
            val pluginDir = home.resolve(".sbt/$it/plugins")
            createTempFile("ort", ".sbt", pluginDir).apply {
                writeText(DEPENDENCY_GRAPH_PLUGIN_DECLARATION)

                // Delete the file again even if the JVM is killed, e.g. by aborting a debug session.
                deleteOnExit()
            }
        }
    }

    override fun resolveDependencies(analyzerRoot: File, definitionFiles: List<File>): ResolutionResult {
        beforeResolution(definitionFiles)

        val workingDir = if (definitionFiles.count() > 1) {
            // Some sbt projects do not have a build file in their root, but they still require "sbt" to be run from the
            // project's root directory. In order to determine the root directory, use the common prefix of all
            // definition file paths.
            getCommonFilePrefix(definitionFiles).also {
                log.info { "Determined '$it' as the $managerName project root directory." }
            }
        } else {
            definitionFiles.first().parentFile
        }

        fun runSbt(vararg command: String) =
                suppressInput {
                    run(workingDir, BATCH_MODE, LOG_NO_FORMAT, *command)
                }

        // Get the list of project names. The current project, which by default is the roo project, is indicated by an
        // asterisk.
        val internalProjects = runSbt("projects").stdout.lines().mapNotNull { line ->
            PROJECT_REGEX.matchEntire(line)?.groupValues?.let {
                it[2] to (it[1] == "*")
            }
        }

        if (internalProjects.isEmpty()) {
            log.warn { "No sbt projects found inside the '${workingDir.absolutePath}' directory." }
        }

        // In contrast to sbt's built-in "update" command, this plugin-provided command creates Ivy reports per
        // sbt project.
        runSbt("ivyReport")

        val sbtProjects = mutableListOf<SbtProject>()

        // Add Ivy report for root projects.
        definitionFiles.mapTo(sbtProjects) { definitionFile ->
            val ivyReportFile = definitionFile.resolveSibling("target").walkTopDown().single {
                it.name == IVY_REPORT_FILE_NAME
            }

            SbtProject(definitionFile.parent, definitionFile, ivyReportFile)
        }

        // Add Ivy reports for programmatically created projects.
        internalProjects.mapNotNullTo(sbtProjects) { (name, isCurrent) ->
            if (isCurrent) {
                null
            } else {
                val ivyReportFile = workingDir.resolve(name).walkTopDown().single {
                    it.name == IVY_REPORT_FILE_NAME
                }

                val definitionFile = definitionFiles.single { it.parentFile == workingDir }
                SbtProject(name, definitionFile, ivyReportFile)
            }
        }

        parseSbtProjects(sbtProjects)

        afterResolution(definitionFiles)

        return emptyMap<File, ProjectAnalyzerResult>().toMutableMap()
    }

    override fun resolveDependencies(definitionFile: File) =
            // This is not implemented in favor over overriding [resolveDependencies].
            throw NotImplementedError()

    override fun afterResolution(definitionFiles: List<File>) {
        log.info { "Removing $DEPENDENCY_GRAPH_PLUGIN_NAME plugin version $DEPENDENCY_GRAPH_PLUGIN_VERSION..." }

        pluginDeclarationFiles.forEach {
            // Delete the file as early as possible even before the JVM exits.
            it.delete()
        }
    }

    private fun parseSbtProjects(sbtProjects: List<SbtProject>) {
        xmlMapper.registerModule(SimpleModule().addDeserializer(Any::class.java,
                DeduplicatingUntypedObjectDeserializer))

        sbtProjects.forEach { sbtProject ->
            val scopeFiles = sbtProject.ivyReportFile.parentFile.listFiles { _, name -> name.endsWith(".xml") }
            scopeFiles.forEach { scopeFile ->
                log.info { "Parsing file '$scopeFile'..." }

                val ivyReport = xmlMapper.readValue(scopeFile, Any::class.java) as LinkedHashMap<*, *>

                val info = ivyReport["info"] as LinkedHashMap<*, *>
                val scope = Scope(info["conf"] as String)

                val dependencies = ivyReport["dependencies"] as LinkedHashMap<*, *>
                dependencies.forEach { _, value ->
                    val module = value as LinkedHashMap<*, *>
                    val revision = module["revision"] as LinkedHashMap<*, *>
                    val id = Identifier(managerName, module["organisation"] as String, module["name"] as String,
                            revision["name"] as String)
                    scope.dependencies += PackageReference(id)
                }
            }
        }
    }
}
