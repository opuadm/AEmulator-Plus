package app.aemu.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BootClasspathRelocateTest {
    private fun tree(vararg files: String): File {
        val root = File.createTempFile("bcp", "").apply { delete(); mkdirs() }
        for (f in files) File(root, f).apply { parentFile.mkdirs(); writeText("x") }
        return root
    }

    @Test fun jarThatMovedToAnotherModuleIsFoundThere() {
        val root = tree("apex/com.android.art/javalib/core-oj.jar", "apex/com.android.i18n/javalib/core-icu4j.jar")
        val list = "/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-icu4j.jar:/system/framework/framework.jar"
        assertEquals("/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/system/framework/framework.jar",
            BootClasspathRepair.relocate(root, list))
    }

    @Test fun existingEntriesAndUnknownJarsStay() {
        val root = tree("apex/com.android.art/javalib/core-oj.jar")
        val list = "/apex/com.android.art/javalib/core-oj.jar:/system/framework/framework-atb-backward-compatibility.jar"
        assertEquals(list, BootClasspathRepair.relocate(root, list))
    }
}
