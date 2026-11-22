/* Adapted from AEmulator Sunset FirmwareContainers.kt (2026-10-07), GPL-3.0; see LICENSE and NOTICE.md.
 * Only the signature and path-safety helpers are used by AEmulator Plus. */
package app.aemu.importer

/** Compression signatures take precedence over misleading download suffixes (e.g. .tgz.tar). */
internal object FirmwareContainers {
    enum class Compression { GZIP, XZ, BZIP2 }
    fun compression(h: ByteArray): Compression? = when {
        h.size >= 2 && h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte() -> Compression.GZIP
        h.size >= 6 && h.copyOfRange(0, 6).contentEquals(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0)) -> Compression.XZ
        h.size >= 3 && h[0] == 'B'.code.toByte() && h[1] == 'Z'.code.toByte() && h[2] == 'h'.code.toByte() -> Compression.BZIP2
        else -> null
    }
    private fun requireSafeRelative(relative: String) {
        require(!relative.startsWith('/') && !relative.contains('\\') && !relative.contains('\u0000') &&
            relative.split('/').none { it == ".." }) { "Unsafe firmware path" }
    }

    /**
     * Only the folder that holds [file] is resolved, never [file] itself: the entry may be a symlink (one made a moment ago,
     * or one of a pair that points at each other once several images are merged), and resolving it is exactly what makes the
     * system answer "too many symbolic links". A parent that cannot be resolved because of a link loop is not an escape:
     * nothing can be written through it, so the caller's own write or symlink call fails on that entry alone.
     */
    private fun requireInside(root: java.io.File, file: java.io.File) {
        val parent = file.parentFile ?: return
        val rootReal = root.canonicalPath
        val real = try { parent.canonicalPath } catch (e: java.io.IOException) { return }
        require(real == rootReal || real.startsWith(rootReal + java.io.File.separator)) { "Firmware path escapes guest root" }
    }

    fun destination(root: java.io.File, relative: String): java.io.File {
        requireSafeRelative(relative)
        val file = java.io.File(root, relative)
        requireInside(root, file)
        return file
    }

    fun checkLink(relative: String, target: String) {
        require(target.isNotEmpty() && !target.contains('\\') && !target.contains('\u0000')) { "Unsafe firmware symlink" }
        val parent = relative.substringBeforeLast('/', "")
        val path = if (target.startsWith('/')) target.trimStart('/') else if (parent.isEmpty()) target else "$parent/$target"
        require(!java.nio.file.Paths.get(path).normalize().startsWith("..")) { "Firmware symlink escapes guest root" }
    }
}
