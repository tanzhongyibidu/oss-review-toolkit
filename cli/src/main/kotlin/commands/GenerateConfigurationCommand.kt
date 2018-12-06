/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
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

package com.here.ort.commands

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.here.ort.CommandWithHelp
import com.here.ort.model.AnalyzerRun
import com.here.ort.model.OrtIssue
import com.here.ort.model.OrtResult
import com.here.ort.model.Project
import com.here.ort.model.config.CopyrightGarbage
import com.here.ort.model.config.ErrorResolution
import com.here.ort.model.config.ErrorResolutionReason
import com.here.ort.model.config.Excludes
import com.here.ort.model.config.ProjectExclude
import com.here.ort.model.config.ProjectExcludeReason
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.model.config.Resolutions
import com.here.ort.model.config.ScopeExclude
import com.here.ort.model.config.ScopeExcludeReason
import com.here.ort.model.readValue
import com.here.ort.model.yamlMapper
import com.here.ort.reporter.DefaultResolutionProvider
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL
import java.io.File

@Parameters(commandNames = ["generate-config"], commandDescription = "Generates an ort.yml file.")
object GenerateConfigurationCommand : CommandWithHelp() {
    @Parameter(description = "The input ort result file.",
            names = ["--input-file", "-i"],
            required = true,
            order = PARAMETER_ORDER_MANDATORY)
    private lateinit var inputFile: File

    @Parameter(description = "Outputfile.",
            names = ["--output-file", "-o"],
            required = true,
            order = PARAMETER_ORDER_MANDATORY)
    private lateinit var outputFile: File

    @Parameter(description = "A file containing error resolutions.",
            names = ["--resolutions-file"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var resolutionsFile: File? = null

    private val resolutionProvider = DefaultResolutionProvider()

    override fun runCommand(jc: JCommander): Int {
        val ortResult: OrtResult = inputFile.readValue()

        ortResult.repository.config.resolutions?.let { resolutionProvider.add(it) }
        resolutionsFile?.readValue<Resolutions>()?.let { resolutionProvider.add(it) }

        val config = generateConfig(ortResult)

        yamlMapper.writeValue(outputFile, config)
        println("configuration written to: '${outputFile.absolutePath}'")

        return 0
    }

    private fun generateConfig(ortResult: OrtResult) : RepositoryConfiguration =
        RepositoryConfiguration(
                excludes = Excludes(
                        projects = generateProjectExcludes(ortResult),
                        scopes = generateScopeExcludes(ortResult)
                ),
                resolutions = generateResolutions(ortResult)
        )

    private fun getDistinctDefinitionFilePaths(ortResult: OrtResult) : Map<String, MutableList<Project>> {
        val analyzerRun = ortResult.analyzer!!
        val result = mutableMapOf<String, MutableList<Project>>()
        analyzerRun.result.projects.forEach { project ->
            val key = project.definitionFilePath
            if (key.isNotEmpty()) {
                if (!result.containsKey(key)) {
                    result[key] = mutableListOf()
                }
                result[key]!!.add(project)
            }
        }
        return result
    }

    private fun generateProjectExcludes(ortResult: OrtResult): List<ProjectExclude> {
        val definitionFilePaths = getDistinctDefinitionFilePaths(ortResult)
        return definitionFilePaths.mapNotNull {
            val moreUniqueDefinitionFilePaths = it.value.map { project ->
                 project.vcsProcessed.url.removePrefix("ssh://gerrit.it.here.com:29418/") +
                     "/" + it.key
            }
            val projectList = moreUniqueDefinitionFilePaths.joinToString (prefix = "'", separator = "','", postfix = "'")

            val comment = if (ortResult.repository.vcs.type != "GitRepo") { "This project is not distributed with the release." }
            else if (moreUniqueDefinitionFilePaths.size > 1) {
                "The projects $projectList are not distributed with the release."
            } else {
                "The project $projectList is not distributed with the release."
            }

            ProjectExclude(
                    path = it.key,
                    reason = ProjectExcludeReason.OPTIONAL_COMPONENT_OF,
                    comment = comment
            )
        }
    }

    private fun generateScopeExcludes(ortResult: OrtResult): List<ScopeExclude> {
        val result = mutableListOf<ScopeExclude>()

        val projectTypes = ortResult.analyzer!!.result.projects.mapNotNull { it.id.type }.toSortedSet()
        projectTypes.forEach { type ->
            result.addAll(getScopeExcludes(type))
        }



        return result.toList()
    }

    private fun getScopeExcludes(type: String): List<ScopeExclude> =
        when(type) {
            "Bower" -> listOf(
                    ScopeExclude(
                            name = "devDependencies",
                            reason = ScopeExcludeReason.BUILD_TOOL_OF,
                            comment = "These are dependencies only used for development. They are not distributed in the context of this product."
                    )
            )
            "Bundler" -> listOf(
                    ScopeExclude(
                            name = "test",
                            reason = ScopeExcludeReason.TEST_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    )
            )
            "Gradle" -> listOf(
                    ScopeExclude(
                            name = "test.*",
                            reason = ScopeExcludeReason.TEST_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    ),
                    ScopeExclude(
                            name = ".*Test.*",
                            reason = ScopeExcludeReason.TEST_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    ),
                    ScopeExclude(
                            name = "jacocoAnt",
                            reason = ScopeExcludeReason.BUILD_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    ),
                    ScopeExclude(
                            name = "lintClassPath",
                            reason = ScopeExcludeReason.BUILD_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    ),
                    ScopeExclude(
                            name = "detekt",
                            reason = ScopeExcludeReason.BUILD_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    )
            )
            "Maven" -> listOf(
                    ScopeExclude(
                            name = "provided",
                            reason = ScopeExcludeReason.PROVIDED_BY,
                            comment = "These are dependencies are provided by the user. They are not distributed in the context of this project."
                    ),
                    ScopeExclude(
                            name = "test",
                            reason = ScopeExcludeReason.TEST_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    )
            )
            "NPM" -> listOf(
                    ScopeExclude(
                            name = "devDependencies",
                            reason = ScopeExcludeReason.BUILD_TOOL_OF,
                            comment = "These are dependencies only used for development. They are not distributed in the context of this product."
                    )
            )
            "SBT" -> listOf(
                    ScopeExclude(
                            name = "provided",
                            reason = ScopeExcludeReason.PROVIDED_BY,
                            comment = "These are dependencies provided by the JDK or container at runtime. They are not distributed in the context of this project."
                    ),
                    ScopeExclude(
                            name = "test",
                            reason = ScopeExcludeReason.TEST_TOOL_OF,
                            comment = "These are dependencies used for testing. They are not distributed in the context of this project."
                    )
            )
            "Stack" -> listOf(
                        ScopeExclude(
                                name = "bench",
                                reason = ScopeExcludeReason.TEST_TOOL_OF,
                                comment = "These are dependencies only used for benchmarks. They are not distributed in the context of this product."
                        ),
                        ScopeExclude(
                                name = "test",
                                reason = ScopeExcludeReason.TEST_TOOL_OF,
                                comment = "These are dependencies only used for testing. They are not distributed in the context of this product."
                        )
            )
            "Yarn" -> listOf(
                    ScopeExclude(
                            name = "devDependencies",
                            reason = ScopeExcludeReason.BUILD_TOOL_OF,
                            comment = "These are dependencies only used for development. They are not distributed in the context of this product."
                    )
            )
            "PhpComposer" -> listOf(
                    ScopeExclude(
                            name = "require-dev",
                            reason = ScopeExcludeReason.BUILD_TOOL_OF,
                            comment = "These are dependencies only used for development. They are not distributed in the context of this product."
                    )
            )
            else -> emptyList()
        }

    private fun generateResolutions(ortResult: OrtResult) =
            Resolutions(errors = generateErrorResulutions(ortResult))


    private fun generateErrorResulutions(ortResult: OrtResult) : List<ErrorResolution> {
        val result = mutableMapOf<String, ErrorResolution>()
        ortResult.scanner?.results?.scanResults?.forEach { container ->
            container.results.forEach {
                it.summary.errors.forEach { error ->
                    if (!error.isResolved()) {
                        getResolution(error)?.let {
                            result["${error.source}:${error.message}"] = it
                        }
                    }
                }
            }
        }
        return result.values.sortedBy { it.message }
    }

    private fun getResolution(error: OrtIssue) : ErrorResolution? {
        if (error.message.startsWith("ERROR: Timeout")) {
            return ErrorResolution(
                    message = error.message,
                    reason = ErrorResolutionReason.SCANNER_ISSUE,
                    comment = ""
            )
        }
        return null
    }

    private fun OrtIssue.isResolved() = resolutionProvider.getErrorResolutionsFor(this).isNotEmpty()
}
