// "Add 1st parameter to constructor 'Foo'" "true"

class Foo(val name: String)

fun test() {
    val foo = Foo(<caret>1, "name")
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddFunctionParametersFix