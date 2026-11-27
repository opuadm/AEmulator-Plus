import app.aemu.core.*
import java.io.File
fun main(a: Array<String>) {
    if (a[0] == "--shapes") { dumpShapes(a[1]); return }
    if (a[0] == "--enc") {
        for (l in generateSequence(::readLine)) { try { println(Integer.toHexString(encodeOf(l.trim()))) } catch (e: Exception) { println("ERR") } }
        return
    }
    if (a[0] == "--words") {   // stdin: "hex [prevhex]" per line -> translated lines separated by ';'
        for (l in generateSequence(::readLine)) {
            val p = l.trim().split(" ").filter { it.isNotEmpty() }
            val w = java.lang.Long.parseLong(p[0], 16).toInt(); val pv = if (p.size > 1) java.lang.Long.parseLong(p[1], 16).toInt() else 0
            try { println(linesOf(w, pv)?.joinToString(";") ?: "UNDECODABLE") } catch (e: Exception) { println("ERR ${e.message}") }
        }
        return
    }
    for (p in a) {
        val f = File(p)
        try {
            val r = Iwmmxt.translate(f.readBytes())
            println("${f.name}: " + (r?.summary() ?: "nothing"))
            if (r != null && System.getenv("OUT") != null) File(System.getenv("OUT"), f.name).writeBytes(r.data)
        } catch (e: Exception) { println("${f.name}: FAIL ${e.message}") }
    }
}
