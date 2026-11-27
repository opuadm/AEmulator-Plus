/* AEmulator Sunset addition, 2026-10-02. Modified for AEmulator Plus, 2026-11-09: one lock file per VM slot; 2026-11-24: releaseVm(), overlapping lock reported as busy. GPL-3.0; see LICENSE. */
package app.aemu.core

import app.aemu.R
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Separate from UI status: even failed/stopping guests may still have writers.
 * A VM retains its slot's lease until process death; reset/export/import take the lease of every slot, so they
 * refuse to run while any machine is up. */
internal class VmStorageLease(private val directory: File, private val slots: List<Int> = (0 until VmSlots.COUNT).toList()) : AutoCloseable {
    companion object {
        private var vmLease: VmStorageLease? = null
        /** Used only in a VM process: that process's own slot. Retained across Activity recreation and released by the kernel. */
        @Synchronized fun forVm(directory: File): VmStorageLease =
            vmLease ?: VmStorageLease(directory, listOf(VmSlots.current())).also { vmLease = it }

        /** Gives up this VM process's own lease. A process cannot lock a file it already holds (the JVM throws
         * OverlappingFileLockException), so code that takes every slot's lease inside the VM process (the OTA installer)
         * must call this first. The process is restarted afterwards, which takes the lease again. */
        @Synchronized fun releaseVm() { vmLease?.close() }

        /** slot 0 keeps the original file name */
        private fun lockName(slot: Int) = if (slot <= 0) "vm-storage.lock" else "vm-storage-$slot.lock"
    }

    private class Held(val handle: RandomAccessFile, val lock: FileLock)
    private val held = ArrayList<Held>()

    @Synchronized fun acquire() {
        if (held.isNotEmpty() && held.all { it.lock.isValid }) return
        release()
        directory.mkdirs()
        try {
            for (slot in slots) {
                val opened = RandomAccessFile(File(directory, lockName(slot)), "rw")
                try {
                    val lock = (try { opened.channel.tryLock() } catch (_: OverlappingFileLockException) { null }) ?: localizedError("Shut down all VMs before modifying or exporting VM files", R.string.err_shut_down_all_vms_before_modifying)
                    held += Held(opened, lock)
                } catch (t: Throwable) { opened.close(); throw t }
            }
        } catch (t: Throwable) { release(); throw t }
    }

    private fun release() {
        for (h in held) {
            runCatching { h.lock.release() }
            runCatching { h.handle.close() }
        }
        held.clear()
    }

    @Synchronized override fun close() { release() }
}
