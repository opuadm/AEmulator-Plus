/* AEmulator Plus, 2026-11-24: system updates the guest downloaded and asked its recovery to install. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * The guest's updater (RecoverySystem.installPackage, a vendor FOTA client) writes the package's path into
 * /cache/recovery/command (one recovery argument per line, "--update_package=<path>") and reboots with the reason
 * "recovery". A real recovery would read that file, verify and apply the package, and boot the system again. Here the
 * stock recovery often cannot run at all, so the app reads the same file itself and applies the package with [DeltaOta]
 * (see VmActivity.installPendingOta): the VM is stopped, the package is installed on the VM's files, the command file
 * is cleared like a recovery does at its end, and the system starts again.
 *
 * Only a package file the host can reach is taken: "@<block map>" is followed to the package it names (first line of the map),
 * and neither is a command without --update_package (a plain "reboot to recovery", a wipe).
 * Those fall through to the ordinary recovery boot.
 */
object OtaRecovery {
    /** An update the guest asked to install: [zip] is the package on the host, [command] the file that asked for it. */
    class Pending(val zip: File, val command: File, val wipeCache: Boolean)

    private const val UPDATE = "--update_package="
    private const val WIPE_CACHE = "--wipe_cache"

    /** Where the guest's /cache/recovery/command is on the host. */
    fun commandFile(paths: VmPaths) = File(paths.root, "cache/recovery/command")

    /** The update waiting in the guest's command file, or null when there is none this app can install. */
    fun pending(paths: VmPaths): Pending? {
        val command = commandFile(paths)
        val text = runCatching { command.takeIf { it.isFile && it.length() <= 64 * 1024 }?.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val args = args(text)
        val arg = args.firstOrNull { it.startsWith(UPDATE) }?.removePrefix(UPDATE) ?: return null
        val sdcard = runCatching { File(File(paths.bin, "sdcard.path").readText().trim()).takeIf { it.isDirectory } }.getOrNull()
        val zip = resolvePackage(paths.root, sdcard, arg) ?: return null
        return Pending(zip, command, args.contains(WIPE_CACHE))
    }

    /** The arguments of a recovery command file: one per line, blank lines dropped. */
    fun args(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * The host file for the package path the guest wrote. [root] is the guest's tree, [sdcard] the folder the guest sees as its
     * shared storage (null when there is none). Handles the forms updaters write: "/cache/x.zip", "CACHE:x.zip", "SDCARD:x.zip",
     * "/data/..." and the usual spellings of the shared storage. Null when the path leaves both folders or is no file.
     */
    fun resolvePackage(root: File, sdcard: File?, arg: String): File? {
        var a = arg.trim()
        // "@/cache/recovery/block.map": what uncrypt (5.0 to 6.0 pre-recovery, 7+ RecoverySystem) leaves in the command file. The first
        // line of the map is the path of the package it was made from, which is the file the host can reach.
        if (a.startsWith("@")) {
            val map = resolveMap(root, a.removePrefix("@")) ?: return null
            a = runCatching { map.useLines { it.firstOrNull() } }.getOrNull()?.trim() ?: return null
        }
        if (a.isEmpty() || a.startsWith("@") || a.split('/').any { it == ".." }) return null
        val file = when {
            a.startsWith("CACHE:") -> File(root, "cache/" + a.removePrefix("CACHE:").trimStart('/'))
            a.startsWith("ROOT:") -> File(root, a.removePrefix("ROOT:").trimStart('/'))
            a.startsWith("SDCARD:") -> sdcard?.let { File(it, a.removePrefix("SDCARD:").trimStart('/')) }
            a.startsWith("/") -> {
                val onSdcard = SHARED.firstOrNull { a.startsWith(it) }
                if (onSdcard != null) sdcard?.let { File(it, a.removePrefix(onSdcard)) } else File(root, a.trimStart('/'))
            }
            else -> null
        } ?: return null
        val canon = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val inside = listOfNotNull(root, sdcard).any { base ->
            val b = runCatching { base.canonicalFile }.getOrNull() ?: return@any false
            canon.path.startsWith(b.path + File.separator)
        }
        return canon.takeIf { inside && it.isFile }
    }

    /** The block map file the guest named, only when it lies under the guest's /cache or /data (the map itself is a few lines of text). */
    private fun resolveMap(root: File, path: String): File? {
        val p = path.trim()
        if (!p.startsWith("/") || p.split('/').any { it == ".." }) return null
        val f = runCatching { File(root, p.trimStart('/')).canonicalFile }.getOrNull() ?: return null
        val inside = listOf("cache", "data").any { d -> runCatching { f.path.startsWith(File(root, d).canonicalFile.path + File.separator) }.getOrDefault(false) }
        return f.takeIf { inside && it.isFile && it.length() <= 1L shl 20 }
    }

    /** Paths under which the guest names its shared storage (longest first, so "/data/media/0/" wins over "/data/media/"). */
    private val SHARED = listOf("/storage/emulated/legacy/", "/storage/emulated/0/", "/storage/self/primary/", "/mnt/shell/emulated/0/",
        "/data/media/0/", "/storage/sdcard0/", "/storage/sdcard1/", "/mnt/sdcard/", "/data/media/", "/sdcard/")

    /**
     * The update went in: what a recovery does at its end. The command file goes (or the guest would install it again), the
     * package goes when it is a download the guest made (under /cache or /data; a file on shared storage is the person's) and,
     * with --wipe_cache, /cache is emptied except the recovery folder.
     */
    fun finish(paths: VmPaths, p: Pending) {
        p.command.delete()
        val canon = runCatching { p.zip.canonicalFile }.getOrNull()
        val own = listOf("cache", "data").map { File(paths.root, it) }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        if (canon != null && own.any { canon.path.startsWith(it.path + File.separator) }) p.zip.delete()
        if (p.wipeCache) File(paths.root, "cache").listFiles()?.filter { it.name != "recovery" && it.name != "lost+found" }?.forEach { it.deleteRecursively() }
    }

    /** The person chose not to install (or it failed and they start the system as it is): only the command file goes. */
    fun dismiss(p: Pending) { p.command.delete() }
}
