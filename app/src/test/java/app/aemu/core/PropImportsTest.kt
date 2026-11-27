package app.aemu.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropImportsTest {
    private fun tree(): File = kotlin.io.path.createTempDirectory("propimp").toFile().also { r ->
        File(r, "system").mkdirs()
        File(r, "system/build.prop").writeText("ro.product.model=Generic\nimport /system/build_\${ro.boot.product.hardware.sku}.prop\n")
        File(r, "system/build_c3qpa.prop").writeText("ro.product.brand=Redmi\nro.product.marketname=Redmi 10\n")
        File(r, "system/build_c3qpb.prop").writeText("ro.product.brand=POCO\nro.product.marketname=POCO C40\nro.miui.product.home=com.mi.android.globallauncher\n")
        File(r, "vendor/overlay").mkdirs()
        File(r, "vendor/overlay/MiuiGLWallpaperOverlay.apk").writeText("x")
    }

    @Test fun picksTheDeviceNamedSkuAndHwc() {
        val r = tree()
        val inf = PropImports.inferBootProps(r, emptyMap())
        assertEquals("c3qpa", inf.props["ro.boot.product.hardware.sku"])
        assertTrue("ro.boot.hwc" !in inf.props)
        val res = PropImports.resolve(r, inf.props)
        assertEquals("Redmi 10", res.props["ro.product.marketname"])
    }

    @Test fun derivesIdentityFromPartitionValues() {
        val d = PropImports.deriveIdentity(mapOf("ro.product.odm.model" to "X1", "ro.product.odm.marketname" to "Phone One",
            "ro.product.vendor.model" to "Y2", "ro.product.system.brand" to "gen"), null)
        assertEquals("X1", d["ro.product.model"])
        assertEquals("Phone One", d["ro.product.marketname"])
        assertEquals("gen", d["ro.product.brand"])
    }

    @Test fun settingsWin() {
        val r = tree()
        val inf = PropImports.inferBootProps(r, mapOf("ro.boot.product.hardware.sku" to "c3qpa"))
        assertTrue("ro.boot.product.hardware.sku" !in inf.props)
        assertEquals("Redmi 10", PropImports.resolve(r, mapOf("ro.boot.product.hardware.sku" to "c3qpa")).props["ro.product.marketname"])
    }
}
