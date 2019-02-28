/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.compilerRunner

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.daemon.client.*
import org.jetbrains.kotlin.daemon.common.*
import org.jetbrains.kotlin.daemon.common.DaemonReportCategory
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.daemon.common.DummyProfiler
import org.jetbrains.kotlin.daemon.common.Profiler
import org.jetbrains.kotlin.daemon.common.WallAndThreadAndMemoryTotalProfiler
import org.jetbrains.kotlin.daemon.common.impls.DummyProfilerAsync
import org.jetbrains.kotlin.daemon.common.impls.ProfilerAsync
import org.jetbrains.kotlin.daemon.common.impls.WallAndThreadAndMemoryTotalProfilerAsync

object KotlinCompilerRunnerUtils {
    fun exitCodeFromProcessExitCode(log: KotlinLogger, code: Int): ExitCode {
        val exitCode = ExitCode.values().find { it.code == code }
        if (exitCode != null) return exitCode

        log.debug("Could not find exit code by value: $code")
        return if (code == 0) ExitCode.OK else ExitCode.COMPILATION_ERROR
    }

    @Synchronized
    @JvmStatic
    fun newDaemonConnection(
        compilerId: CompilerId,
        clientAliveFlagFile: File,
        sessionAliveFlagFile: File,
        messageCollector: MessageCollector,
        isDebugEnabled: Boolean,
        daemonOptions: DaemonOptions = configureDaemonOptions(),
        additionalJvmParams: Array<String> = arrayOf()
    ): CompileServiceSession? {
        val daemonJVMOptions = configureDaemonJVMOptions(
            additionalParams = *additionalJvmParams,
            inheritMemoryLimits = true,
            inheritOtherJvmOptions = false,
            inheritAdditionalProperties = true
        )

        val daemonReportMessages = ArrayList<DaemonReportMessage>()
        val daemonReportingTargets = DaemonReportingTargets(messages = daemonReportMessages)

        val profiler = if (daemonOptions.reportPerf) WallAndThreadAndMemoryTotalProfilerAsync(withGC = false) else DummyProfilerAsync()

        val kotlinCompilerClient = KotlinCompilerDaemonClient.instantiate(Version.RMI)

        val connection = runBlocking {
            profiler.withMeasure(null) {
                kotlinCompilerClient.connectAndLease(
                        compilerId,
                        clientAliveFlagFile,
                        daemonJVMOptions,
                        daemonOptions,
                        daemonReportingTargets,
                        autostart = true,
                        leaseSession = true,
                        sessionAliveFlagFile = sessionAliveFlagFile
                )
            }
        }

        if (connection == null || isDebugEnabled) {
            for (message in daemonReportMessages) {
                val severity = when (message.category) {
                    DaemonReportCategory.DEBUG -> CompilerMessageSeverity.INFO
                    DaemonReportCategory.INFO -> CompilerMessageSeverity.INFO
                    DaemonReportCategory.EXCEPTION -> CompilerMessageSeverity.EXCEPTION
                }
                messageCollector.report(severity, message.message)
            }
        }

        fun reportTotalAndThreadPerf(
            message: String, daemonOptions: DaemonOptions, messageCollector: MessageCollector, profiler: ProfilerAsync
        ) {
            if (daemonOptions.reportPerf) {
                fun Long.ms() = TimeUnit.NANOSECONDS.toMillis(this)
                val counters = profiler.getTotalCounters()
                messageCollector.report(
                    CompilerMessageSeverity.INFO, "PERF: $message ${counters.time.ms()} ms, thread ${counters.threadTime.ms()}"
                )
            }
        }

        reportTotalAndThreadPerf("Daemon connect", daemonOptions, MessageCollector.NONE, profiler)
        return connection?.toRMI()
    }
}

