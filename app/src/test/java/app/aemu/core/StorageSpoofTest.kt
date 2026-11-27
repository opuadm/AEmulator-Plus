package app.aemu.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageSpoofTest {
    /** StorageManager.roundStorageSize of Android: the next 1, 2, 4 ... 512 x 10^n bytes at or above the size. */
    private fun round(size: Long): Long {
        var v = 1L; var pow = 1L
        while (v * pow < size) { v = v shl 1; if (v > 512) { v = 1; pow *= 1000 } }
        return v * pow
    }

    @Test fun everyPresetIsNamedByItsOwnSize() {
        for (gb in StorageSpoof.PRESETS_GB) {
            assertEquals(StorageSpoof.rawBytes(gb), round(StorageSpoof.bytes(gb)), "$gb GB")
            assertEquals(StorageSpoof.rawBytes(gb), round(StorageSpoof.rawBytes(gb)), "$gb GB raw")
            assertTrue(StorageSpoof.bytes(gb) < StorageSpoof.rawBytes(gb))
        }
    }
}
