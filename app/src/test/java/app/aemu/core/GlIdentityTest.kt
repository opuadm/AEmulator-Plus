package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GlIdentityTest {
    private val shim = File.createTempFile("libaemuglid", ".so").apply { deleteOnExit() }

    @Test fun blankChipChangesNothing() {
        val base = mapOf("LD_PRELOAD" to "/n/libglbuf.so")
        assertSame(base, GlIdentity.env("", "", shim, base))
        assertFalse(GlIdentity.active(" ", ""))
    }

    @Test fun missingLibraryChangesNothing() {
        val base = emptyMap<String, String>()
        assertSame(base, GlIdentity.env("ARM", "Mali-T760", File("/nonexistent/libaemuglid.so"), base))
        assertSame(base, GlIdentity.env("ARM", "Mali-T760", null, base))
    }

    @Test fun setsPreloadAndBothStrings() {
        val e = GlIdentity.env("ARM", "Mali-T760", shim, emptyMap())
        assertEquals(shim.absolutePath, e["LD_PRELOAD"])
        assertEquals("ARM", e[GlIdentity.ENV_VENDOR])
        assertEquals("Mali-T760", e[GlIdentity.ENV_RENDERER])
        assertTrue(GlIdentity.applied(e))
    }

    @Test fun keepsAnExistingPreloadBehindTheShim() {
        val e = GlIdentity.env("ARM", "Mali-T760", shim, mapOf("LD_PRELOAD" to "/n/libglbuf.so", "X" to "1"))
        assertEquals(shim.absolutePath + ":/n/libglbuf.so", e["LD_PRELOAD"])
        assertEquals("1", e["X"])
        // applying twice does not repeat it
        assertEquals(e["LD_PRELOAD"], GlIdentity.env("ARM", "Mali-T760", shim, e)["LD_PRELOAD"])
    }

    @Test fun aBlankStringStaysThePhones() {
        val e = GlIdentity.env("", "Mali-T760", shim, mapOf(GlIdentity.ENV_VENDOR to "old"))
        assertFalse(e.containsKey(GlIdentity.ENV_VENDOR))
        assertEquals("Mali-T760", e[GlIdentity.ENV_RENDERER])
    }

    @Test fun appliedNeedsTheShim() {
        assertFalse(GlIdentity.applied(mapOf("LD_PRELOAD" to "/n/libglbuf.so", GlIdentity.ENV_RENDERER to "x")))
        assertFalse(GlIdentity.applied(emptyMap()))
    }
}
