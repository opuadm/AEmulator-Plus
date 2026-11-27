package app.aemu.core
fun dumpShapes(path: String) { java.io.File(path).writeText(Iwmmxt.allLines().joinToString("\n") + "\n") }
fun linesOf(w: Int, p: Int): List<String>? = Iwmmxt.linesFor(w, p)

fun encodeOf(l: String): Int = Iwmmxt.encodeLine(l)
