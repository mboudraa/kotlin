// IGNORE_BACKEND: JVM_IR
// WITH_RUNTIME
// WITH_COROUTINES
// COMMON_COROUTINES_TEST
// CHECK_BYTECODE_LISTING
import helpers.*
import COROUTINES_PACKAGE.*

inline fun foo(crossinline c: suspend () -> Unit): suspend () -> Unit {
    val a = {}
    val b = { a() }
    val cc = { b() }
    val d = { cc()}
    val e = { d() }
    val f = { e() }
    val g = { f() }
    val h = { g() }
    val s = suspend { c() }
    return s
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

fun box(): String {
    var res = "FAIL"
    builder {
        val lambda = suspend {
            res = "OK"
        }
        foo(lambda)()
    }
    return res
}