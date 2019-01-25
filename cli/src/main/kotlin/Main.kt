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

package com.here.ort

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter

import com.here.ort.commands.*
import com.here.ort.utils.PARAMETER_ORDER_LOGGING
import com.here.ort.utils.log
import com.here.ort.utils.printStackTrace

import kotlin.system.exitProcess

const val TOOL_NAME = "ort"

/**
 * The main entry point of the application.
 */
object Main : CommandWithHelp() {
    @Parameter(description = "Enable info logging.",
            names = ["--info"],
            order = PARAMETER_ORDER_LOGGING)
    private var info = false

    @Parameter(description = "Enable debug logging and keep any temporary files.",
            names = ["--debug"],
            order = PARAMETER_ORDER_LOGGING)
    private var debug = false

    @Parameter(description = "Print out the stacktrace for all exceptions.",
            names = ["--stacktrace"],
            order = PARAMETER_ORDER_LOGGING)
    private var stacktrace = false

    /**
     * The entry point for the application.
     *
     * @param args The list of application arguments.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    /**
     * Run the ORT CLI with the provided [args] and return the exit code of [CommandWithHelp.run].
     */
    fun run(args: Array<String>): Int {
        val jc = JCommander(this).apply {
            programName = TOOL_NAME
            addCommand(AnalyzerCommand)
            addCommand(DownloaderCommand)
            addCommand(ExtractCopyrightsCommand)
            addCommand(GenerateConfigurationCommand)
            addCommand(EvaluatorCommand)
            addCommand(FilterCopyrightsCommand)
            addCommand(MergeCopyrightsCommand)
            addCommand(ProcessCopyrightsCommand)
            addCommand(ReporterCommand)
            addCommand(RequirementsCommand)
            addCommand(ScannerCommand)
            parse(*args)
        }

        return run(jc)
    }

    override fun runCommand(jc: JCommander): Int {
        when {
            debug -> log.level = ch.qos.logback.classic.Level.DEBUG
            info -> log.level = ch.qos.logback.classic.Level.INFO
        }

        log.level = ch.qos.logback.classic.Level.INFO
        // Make the parameter globally available.
        printStackTrace = true

        // JCommander already validates the command names.
        val command = jc.commands[jc.parsedCommand]!!
        val commandObject = command.objects.first() as CommandWithHelp

        // Delegate running actions to the specified command.
        return commandObject.run(jc)
    }
}
