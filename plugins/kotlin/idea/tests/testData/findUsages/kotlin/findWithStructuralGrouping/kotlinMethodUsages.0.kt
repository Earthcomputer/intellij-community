// PSI_ELEMENT: org.jetbrains.kotlin.psi.KtNamedFunction
// GROUPING_RULES: org.jetbrains.kotlin.idea.base.searching.usages.KotlinDeclarationGroupingRule
// OPTIONS: usages
package server

public open class Server() {
    open fun <caret>processRequest() = "foo"
}

public class ServerEx() : Server() {
    override fun processRequest() = "foofoo"
}

// FIR_COMPARISON