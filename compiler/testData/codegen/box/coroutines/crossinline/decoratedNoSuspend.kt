// IGNORE_BACKEND: JVM_IR
// TARGET_BACKEND: JVM
// FULL_JDK
// COMMON_COROUTINES_TEST
// WITH_RUNTIME
// WITH_COROUTINES
// CHECK_BYTECODE_LISTING
import helpers.*
import kotlin.concurrent.thread
import COROUTINES_PACKAGE.*

fun decorate(actualWork: suspend () -> Int) = workDecorator(actualWork)

inline fun work(crossinline triggerResume: (Continuation<Int>) -> Unit) = suspend {
    suspendCoroutine<Int> { continuation ->
        triggerResume(continuation)
    }
}

inline fun workDecorator(crossinline work: suspend () -> Int) = suspend {
    work()
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

fun box(): String {
    var res = -1
    builder {
        val decoratedWork = decorate {
            work {
                thread {
                    it.resume(42)
                }.join()
            }()
        }
        res = decoratedWork()
    }
    if (res != 42) return "$res"
    return "OK"
}