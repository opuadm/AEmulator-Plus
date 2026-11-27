/* AEmulator Plus addition: `import` lines of build.prop files. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File

/**
 * Android's property service follows `import <file>` lines in build.prop, expanding `${property}` in the path. Xiaomi
 * uses it for the per-SKU file that holds the marketing name, brand and model (`import /system/build_${ro.boot.product.hardware.sku}.prop`;
 * the bootloader supplies the SKU). [PropArea.parseProps] skips such lines, so those properties never reached the guest.
 */
object PropImports {
    /** The prop files of a tree that can carry imports, in the order init reads them. */
    val FILES = listOf(
        "system/build.prop", "system/system_ext/build.prop", "system/system_ext/etc/build.prop", "system/product/build.prop",
        "system/product/etc/build.prop", "vendor/build.prop", "system/vendor/build.prop", "vendor/odm/etc/build.prop",
        "odm/etc/build.prop", "odm/build.prop", "product/etc/build.prop", "system_ext/etc/build.prop",
    )
    private val VAR = Regex("""\$\{([^}]+)\}""")
    private const val MAX_DEPTH = 3

    /** Boot properties a phone's boot loader supplies that an image needs but the VM settings do not carry. */
    class Inferred(val props: LinkedHashMap<String, String>, val notes: List<String>)

    /**
     * Added for AEmulator Plus (2026-11-23): a Xiaomi image imports `build_${ro.boot.product.hardware.sku}.prop` and its overlays /
     * preinstalled apps look at `ro.boot.hwc`; on the phone the boot loader supplies both, here nothing did, so the model, brand,
     * market name and `ro.miui.product.home` (Poco launcher) never reached the guest. When a `${ro.boot.*}` import has no value and
     * the tree holds files that fit its pattern, take the first one (all candidates are logged; the VM settings can choose another).
     * Whatever the person typed in the settings wins ([known]).
     */
    fun inferBootProps(root: File, known: Map<String, String>, files: List<String> = FILES): Inferred {
        val out = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        val seen = HashSet<String>()
        for (rel in files) {
            val f = File(root, rel)
            val text = runCatching { f.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            for (raw in text.lineSequence()) {
                val ln = raw.trim()
                if (!ln.startsWith("import ")) continue
                val spec = ln.removePrefix("import ").trim()
                val vars = VAR.findAll(spec).map { it.groupValues[1] }.toList()
                if (vars.size != 1) continue
                val name = vars[0]
                if (!name.startsWith("ro.boot.") || !known[name].isNullOrEmpty() || out.containsKey(name) || !seen.add(spec)) continue
                val parts = VAR.split(spec.substringAfterLast('/'))
                if (parts.size != 2) continue
                val dir = File(root, spec.substringBeforeLast('/', "").trimStart('/'))
                val cands = dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.length > parts[0].length + parts[1].length && it.name.startsWith(parts[0]) && it.name.endsWith(parts[1]) }
                    .sortedBy { it.name }
                if (cands.isEmpty()) continue
                fun text(c: File) = runCatching { c.readText(Charsets.UTF_8) }.getOrDefault("")
                fun line(t: String, k: String) = t.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$k=") }?.substringAfter('=').orEmpty()
                // nothing in the firmware says which variant the phone was: the first one, listed so the person can choose another
                val best = cands.first()
                for (c in cands) {
                    val t = text(c)
                    notes.add("  ${c.name}: " + listOf("brand", "model", "marketname").joinToString(" ") { w ->
                        "$w=" + (line(t, "ro.product.$w").ifEmpty { listOf("odm", "vendor", "system", "product").firstNotNullOfOrNull { l -> line(t, "ro.product.$l.$w").ifEmpty { null } } ?: "-" }) })
                }
                val value = best.name.substring(parts[0].length, best.name.length - parts[1].length)
                out[name] = value
                notes.add("$name not supplied: using \"$value\" (from ${cands.joinToString { it.name }}); set it in the VM settings > boot properties to pick another")
            }
        }
        return Inferred(out, notes)
    }

    /**
     * What init of 10+ derives after reading the property files: ro.product.<what> from the first partition (by
     * ro.product.property_source_order) that has ro.product.<partition>.<what>. A SKU file imported from build.prop
     * usually sets only the partition-qualified names (odm / vendor), so without this the guest keeps the generic system
     * values (the codename as model, no market name: the About page names the wrong phone). [props] are the imported
     * properties; [order] the source order. Returns only the properties that come out of the imports.
     */
    fun deriveIdentity(props: Map<String, String>, order: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val sources = (order ?: "product,product_services,odm,vendor,system").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (what in listOf("brand", "device", "manufacturer", "model", "name", "marketname")) {
            val key = "ro.product.$what"
            if (!props[key].isNullOrEmpty()) continue
            val v = sources.firstNotNullOfOrNull { props["ro.product.$it.$what"]?.takeIf { s -> s.isNotEmpty() } } ?: continue
            out[key] = v
        }
        return out
    }

    class Result(val props: LinkedHashMap<String, String>, val notes: List<String>)

    /**
     * The properties of every imported file found under [root]. [known] gives the values for `${...}` (boot properties and
     * the like); properties read from the files themselves count too. An import whose variable has no value is reported in
     * the notes, with the files it could have meant, and skipped - exactly what init does with a missing file.
     */
    fun resolve(root: File, known: Map<String, String>, files: List<String> = FILES): Result {
        val vars = HashMap(known)
        val out = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        val seen = HashSet<String>()

        fun load(f: File, depth: Int) {
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return
            for (raw in text.lineSequence()) {
                val ln = raw.trim()
                if (ln.isEmpty() || ln.startsWith("#")) continue
                if (ln.startsWith("import ")) {
                    if (depth >= MAX_DEPTH) continue
                    val spec = ln.removePrefix("import ").trim()
                    val missing = VAR.findAll(spec).map { it.groupValues[1] }.filter { vars[it].isNullOrEmpty() }.toList()
                    if (missing.isNotEmpty()) {
                        val glob = Regex(VAR.split(spec.substringAfterLast('/')).joinToString("[^/]+") { Regex.escape(it) })
                        val dir = File(root, spec.substringBeforeLast('/', "").trimStart('/'))
                        val cands = dir.list().orEmpty().filter { glob.matches(it) }.sorted()
                        notes.add("import $spec skipped: ${missing.joinToString()} is not set" +
                            if (cands.isNotEmpty()) " (candidates: ${cands.joinToString()})" else "")
                        continue
                    }
                    val path = VAR.replace(spec) { vars.getValue(it.groupValues[1]) }
                    val parts = path.split('/').filter { it.isNotEmpty() }
                    if (!path.startsWith("/") || parts.any { it == ".." || it == "." }) continue
                    val target = File(root, parts.joinToString("/"))
                    if (!target.isFile) { notes.add("import $path: no such file"); continue }
                    if (!seen.add(target.path)) continue
                    notes.add("import $path")
                    load(target, depth + 1)
                    continue
                }
                val i = ln.indexOf('=')
                if (i <= 0) continue
                val k = ln.substring(0, i).trim()
                val v = ln.substring(i + 1).trim()
                if (depth > 0) out[k] = v
                if (!vars.containsKey(k) || vars[k].isNullOrEmpty()) vars[k] = v
            }
        }
        for (rel in files) {
            val f = File(root, rel)
            if (f.isFile) load(f, 0)
        }
        return Result(out, notes)
    }
}
