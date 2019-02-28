/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.daemon.common

import java.io.Serializable
import java.rmi.Remote
import java.rmi.RemoteException

interface CompilationResults : Remote {
    @Throws(RemoteException::class)
    fun add(compilationResultCategory: Int, value: Serializable)
}

enum class CompilationResultCategory(val code: Int) {
    IC_COMPILE_ITERATION(0),
    BUILD_REPORT_LINES(1),
    VERBOSE_BUILD_REPORT_LINES(2),
}