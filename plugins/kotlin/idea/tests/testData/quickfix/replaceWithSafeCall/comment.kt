// "Replace with safe (?.) call" "true"
// WITH_STDLIB
fun foo(a: String?) {
    val b = a // comment1
            // comment2
            .<caret>length
}