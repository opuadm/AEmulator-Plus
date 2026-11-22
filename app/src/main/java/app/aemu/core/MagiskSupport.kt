/* AEmulator Plus addition, 2026-11-21. GPL-3.0; see LICENSE. */
package app.aemu.core

import android.content.Context
import android.net.Uri
import java.io.File

/** Android side of [MagiskCore]: installing a picked Magisk APK into a stopped VM and taking it out again. */
internal object MagiskSupport {
    /**
     * Offered for the native engine and an arm64 firmware with a 64-bit side. A firmware that is 32-bit only would need
     * the armeabi-v7a build under qemu-arm, which has no setns/mount stand-ins: not done.
     */
    fun offered(img: GuestImage, root: File): Boolean =
        img.effective().engine == Engine.N64 && img.arch == GuestArch.ARM && MagiskCore.hasArm64Userland(root)

    /** [uri] is the Magisk app APK (the full app, not the stub). Stop all VMs first, as for the other imports. */
    fun install(ctx: Context, id: String, uri: Uri): MagiskCore.Info =
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: error("VM no longer exists")
            val apk = File(ctx.cacheDir, "magisk-${System.nanoTime()}.apk")
            try {
                (ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the APK")).use { i ->
                    apk.outputStream().use { i.copyTo(it) }
                }
                val info = MagiskCore.install(paths.root, apk)
                ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
                info
            } finally { apk.delete() }
        }

    fun remove(ctx: Context, id: String) {
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: error("VM no longer exists")
            MagiskCore.uninstall(paths.root)
            ImageStore.save(ctx, old.copy(settings = old.settings.copy(magisk = false), sizeBytes = ImageStore.du(paths.dir)))
        }
    }
}
