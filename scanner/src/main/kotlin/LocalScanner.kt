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

package com.here.ort.scanner

import ch.frankel.slf4k.*

import com.fasterxml.jackson.databind.JsonNode

import com.here.ort.downloader.DownloadException
import com.here.ort.downloader.Downloader
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.EMPTY_JSON_NODE
import com.here.ort.model.Environment
import com.here.ort.model.Identifier
import com.here.ort.model.OrtIssue
import com.here.ort.model.OrtResult
import com.here.ort.model.Package
import com.here.ort.model.Provenance
import com.here.ort.model.Repository
import com.here.ort.model.ScanRecord
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanResultContainer
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.ScannerRun
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.model.mapper
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.OS
import com.here.ort.utils.collectMessagesAsString
import com.here.ort.utils.fileSystemEncode
import com.here.ort.utils.getPathFromEnvironment
import com.here.ort.utils.log
import com.here.ort.utils.safeMkdirs
import com.here.ort.utils.showStackTrace

import com.vdurmont.semver4j.Requirement

import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Implementation of [Scanner] for scanners that operate locally. Packages passed to [scanPackages] are processed in
 * serial order. Scan results can be stored in a [ScanResultsStorage].
 */
abstract class LocalScanner(name: String, config: ScannerConfiguration) : Scanner(name, config), CommandLineTool {
    /**
     * A property containing the file name extension of the scanner's native output format, without the dot.
     */
    abstract val resultFileExt: String

    /**
     * The directory the scanner was bootstrapped to, if so.
     */
    protected val scannerDir by lazy {
        val scannerExe = command()

        getPathFromEnvironment(scannerExe)?.parentFile?.takeIf {
            getVersion(it) == scannerVersion
        } ?: run {
            if (scannerExe.isNotEmpty()) {
                log.info {
                    "Bootstrapping scanner '$scannerName' as required version $scannerVersion was not found in PATH."
                }

                bootstrap().also {
                    val actualScannerVersion = getVersion(it)
                    if (actualScannerVersion != scannerVersion) {
                        throw IOException("Bootstrapped scanner version $actualScannerVersion " +
                                "does not match expected version $scannerVersion.")
                    }
                }
            } else {
                log.info { "Skipping to bootstrap scanner '$scannerName' as it has no executable." }

                File("")
            }
        }
    }

    /**
     * The required version of the scanner. This is also the version that would get bootstrapped.
     */
    protected abstract val scannerVersion: String

    /**
     * The full path to the scanner executable.
     */
    protected val scannerPath by lazy { File(scannerDir, command()) }

    override fun getVersionRequirement(): Requirement = Requirement.buildLoose(scannerVersion)

    /**
     * Return the actual version of the scanner, or an empty string in case of failure.
     */
    abstract fun getVersion(dir: File = scannerDir): String

    /**
     * Bootstrap the scanner to be ready for use, like downloading and / or configuring it.
     *
     * @return The directory the scanner is installed in.
     */
    protected open fun bootstrap(): File = throw NotImplementedError()

    /**
     * Return the configuration of this [LocalScanner].
     */
    abstract fun getConfiguration(): String

    /**
     * Return the [ScannerDetails] of this [LocalScanner].
     */
    fun getDetails() = ScannerDetails(scannerName, getVersion(), getConfiguration())

    override fun scanPackages(packages: List<Package>, outputDirectory: File, downloadDirectory: File)
            : Map<Package, List<ScanResult>> {
        val scannerDetails = getDetails()

        return packages.withIndex().associate { (index, pkg) ->
            val result = try {
                log.info { "Starting scan of '${pkg.id.toCoordinates()}' (${index + 1}/${packages.size})." }

                scanPackage(scannerDetails, pkg, outputDirectory, downloadDirectory).map {
                    // Remove the now unneeded reference to rawResult here to allow garbage collection to clean it up.
                    it.copy(rawResult = null)
                }
            } catch (e: ScanException) {
                e.showStackTrace()

                log.error { "Could not scan '${pkg.id.toCoordinates()}': ${e.collectMessagesAsString()}" }

                val now = Instant.now()
                listOf(ScanResult(
                        provenance = Provenance(),
                        scanner = scannerDetails,
                        summary = ScanSummary(
                                startTime = now,
                                endTime = now,
                                fileCount = 0,
                                licenseFindings = sortedSetOf(),
                                errors = listOf(OrtIssue(source = scannerName, message = e.collectMessagesAsString()))
                        ),
                        rawResult = EMPTY_JSON_NODE)
                )
            }

            Pair(pkg, result)
        }
    }

    /**
     * Scan the provided [pkg] for license information and write the results to [outputDirectory] using the scanner's
     * native file format. The results file name is derived from [pkg] and [scannerDetails].
     *
     * If a scan result is found in the storage, it is used without running the actual scan. If no stored scan result is
     * found, the package's source code is downloaded to [downloadDirectory] and scanned afterwards.
     *
     * The return value is a list of [ScanResult]s. If a package could not be scanned, a [ScanException] is thrown.
     */
    private fun scanPackage(scannerDetails: ScannerDetails, pkg: Package, outputDirectory: File,
                    downloadDirectory: File): List<ScanResult> {
        val scanResultsForPackageDirectory = File(outputDirectory, pkg.id.toPath()).apply { safeMkdirs() }
        val resultsFile = File(scanResultsForPackageDirectory, "scan-results_${scannerDetails.name}.$resultFileExt")

        val storedResults = ScanResultsStorage.read(pkg, scannerDetails)

        if (storedResults.results.isNotEmpty()) {
            // Some external tools rely on the raw results filer to be written to the scan results directory, so write
            // the first stored result to resultsFile. This feature will be removed when the reporter tool becomes
            // available.
            resultsFile.mapper().writeValue(resultsFile, storedResults.results.first().rawResult)
            return storedResults.results
        }

        val downloadResult = try {
            Downloader().download(pkg, downloadDirectory)
        } catch (e: DownloadException) {
            e.showStackTrace()

            log.error { "Could not download '${pkg.id.toCoordinates()}': ${e.collectMessagesAsString()}" }

            val now = Instant.now()
            val scanResult = ScanResult(
                    Provenance(),
                    scannerDetails,
                    ScanSummary(
                            startTime = now,
                            endTime = now,
                            fileCount = 0,
                            licenseFindings = sortedSetOf(),
                            errors = listOf(OrtIssue(source = scannerName, message = e.collectMessagesAsString()))
                    ),
                    EMPTY_JSON_NODE
            )
            return listOf(scanResult)
        }

        log.info {
            "Running $scannerDetails on directory '${downloadResult.downloadDirectory.absolutePath}'."
        }

        val provenance = Provenance(downloadResult.dateTime, downloadResult.sourceArtifact, downloadResult.vcsInfo,
                downloadResult.originalVcsInfo)
        val scanResult = scanPath(downloadResult.downloadDirectory, resultsFile).copy(provenance = provenance)

        ScanResultsStorage.add(pkg.id, scanResult)

        return listOf(scanResult)
    }

    /**
     * Scan the provided [inputPath] for license information and write the results to [outputDirectory] using the
     * scanner's native file format. The results file name is derived from [inputPath] and [getDetails].
     *
     * No scan results storage is used by this function.
     *
     * The return value is an [OrtResult]. If the path could not be scanned, a [ScanException] is thrown.
     */
    fun scanInputPath(inputPath: File, outputDirectory: File): OrtResult {
        val startTime = Instant.now()

        val absoluteInputPath = inputPath.absoluteFile

        require(inputPath.exists()) {
            "Specified path '$absoluteInputPath' does not exist."
        }

        val scannerDetails = getDetails()
        log.info { "Scanning path '$absoluteInputPath' with $scannerDetails..." }

        val result = try {
            val resultsFile = File(outputDirectory.apply { safeMkdirs() },
                    "${inputPath.nameWithoutExtension}_${scannerDetails.name}.$resultFileExt")
            scanPath(inputPath, resultsFile).also {
                log.info {
                    "Detected licenses for path '$absoluteInputPath': ${it.summary.licenses.joinToString()}"
                }
            }
        } catch (e: ScanException) {
            e.showStackTrace()

            log.error { "Could not scan path '$absoluteInputPath': ${e.message}" }

            val now = Instant.now()
            val summary = ScanSummary(now, now, 0, sortedSetOf(),
                    listOf(OrtIssue(source = scannerName, message = e.collectMessagesAsString())))
            ScanResult(Provenance(), getDetails(), summary)
        }

        // There is no package id for arbitrary paths so create a fake one, ensuring that no ":" is contained.
        val id = Identifier(OS.name.fileSystemEncode(), absoluteInputPath.parent.fileSystemEncode(),
                inputPath.name.fileSystemEncode(), "")

        val scanResultContainer = ScanResultContainer(id, listOf(result))
        val scanRecord = ScanRecord(sortedSetOf(), sortedSetOf(scanResultContainer), ScanResultsStorage.stats)

        val endTime = Instant.now()

        val scannerRun = ScannerRun(startTime, endTime, Environment(), config, scanRecord)

        val vcs = VersionControlSystem.getCloneInfo(inputPath)
        val repository = Repository(vcs, vcs.normalize(), RepositoryConfiguration())

        return OrtResult(repository, scanner = scannerRun)
    }

    /**
     * Scan the provided [path] for license information and write the results to [resultsFile] using the scanner's
     * native file format.
     *
     * No scan results storage is used by this function.
     *
     * The return value is a [ScanResult]. If the path could not be scanned, a [ScanException] is thrown.
     */
    abstract fun scanPath(path: File, resultsFile: File): ScanResult

    internal abstract fun getResult(resultsFile: File): JsonNode

    internal abstract fun generateSummary(startTime: Instant, endTime: Instant, result: JsonNode): ScanSummary
}
