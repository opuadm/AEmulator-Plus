/* AEmulator Plus, 2026-11-24: Marvell FileMonitorService in the init plan. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MarvellFileMonitorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(daemon: Boolean = true, lib: Boolean = true): File {
        val r = tmp.newFolder()
        File(r, "system/bin").mkdirs(); File(r, "system/lib").mkdirs()
        if (daemon) File(r, "system/bin/FileMonitorService").writeText("x")
        if (lib) File(r, "system/lib/libFileMonitorService.so").writeText("x")
        return r
    }

    @Test fun startedWhenTheTreeHasIt() {
        val s = InitPlan.marvellFileMonitor(tree(), emptyList())
        assertNotNull(s)
        assertEquals(listOf("/system/bin/FileMonitorService"), s!!.argv)
        assertEquals(true, s.optional)
    }

    @Test fun notWhenTheDaemonOrItsLibraryIsMissing() {
        assertNull(InitPlan.marvellFileMonitor(tree(daemon = false), emptyList()))
        assertNull(InitPlan.marvellFileMonitor(tree(lib = false), emptyList()))
    }

    @Test fun notTwiceWhenTheRcAlreadyRunsIt() {
        val planned = listOf(GuestService("FileMonitorService", listOf("/system/bin/FileMonitorService")))
        assertNull(InitPlan.marvellFileMonitor(tree(), planned))
        assertNull(InitPlan.marvellFileMonitor(tree(), listOf(GuestService("fm", listOf("/system/bin/FileMonitorService")))))
    }
}
