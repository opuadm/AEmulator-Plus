package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OtaRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun layout(): Pair<File, File> {
        val root = tmp.newFolder("root"); val sd = tmp.newFolder("sd")
        File(root, "cache").mkdirs(); File(root, "data/media/0").mkdirs()
        File(root, "cache/a.zip").writeText("x"); File(sd, "b.zip").writeText("x"); File(root, "data/ota.zip").writeText("x")
        return root to sd
    }

    @Test fun readsCommandLines() {
        assertEquals(listOf("--update_package=/cache/a.zip", "--locale=en_US"), OtaRecovery.args("--update_package=/cache/a.zip\r\n\n --locale=en_US \n"))
    }

    @Test fun resolvesCacheForms() {
        val (root, sd) = layout()
        assertEquals(File(root, "cache/a.zip").canonicalFile, OtaRecovery.resolvePackage(root, sd, "/cache/a.zip"))
        assertEquals(File(root, "cache/a.zip").canonicalFile, OtaRecovery.resolvePackage(root, sd, "CACHE:a.zip"))
        assertEquals(File(root, "data/ota.zip").canonicalFile, OtaRecovery.resolvePackage(root, sd, "/data/ota.zip"))
    }

    @Test fun mapsSharedStorageSpellingsToTheSdcardFolder() {
        val (root, sd) = layout()
        val want = File(sd, "b.zip").canonicalFile
        for (p in listOf("/sdcard/b.zip", "/storage/emulated/0/b.zip", "/storage/emulated/legacy/b.zip", "/data/media/0/b.zip", "/mnt/sdcard/b.zip", "SDCARD:b.zip"))
            assertEquals(p, want, OtaRecovery.resolvePackage(root, sd, p))
    }

    @Test fun refusesWhatItCannotRead() {
        val (root, sd) = layout()
        assertNull(OtaRecovery.resolvePackage(root, sd, "@/cache/recovery/block.map"))
        assertNull(OtaRecovery.resolvePackage(root, sd, "/cache/../../etc/passwd"))
        assertNull(OtaRecovery.resolvePackage(root, sd, "/cache/missing.zip"))
        assertNull(OtaRecovery.resolvePackage(root, null, "/sdcard/b.zip"))
        assertNull(OtaRecovery.resolvePackage(root, sd, ""))
    }
}
