// PSI_ELEMENT: org.jetbrains.kotlin.psi.KtNamedFunction
// OPTIONS: usages

class B(val n: Int) {
    operator fun <caret>set(i: Int, a: B) {}
    operator fun inc(): B = TODO()
    operator fun get(i: Int): B = TODO()
}

fun test() {
    var a = B(1)
    a.set(2, B(2))
    a[1] = B(2)
    a[2]++
    a[3]
}
// FIR_COMPARISON