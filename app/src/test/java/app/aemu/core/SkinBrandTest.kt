package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinBrandTest {
    private fun skin(name: String, brand: String?) = VmTheme(id = "skin-$name", name = name, builtIn = true, fromSkinPack = true, brand = brand)

    @Test fun groupsByBrandAlphabeticallyWithUnbrandedLast() {
        val groups = VmThemes.skinsByBrand(listOf(skin("Xperia Z", "Sony"), skin("Odd", null), skin("Galaxy S", "Samsung"), skin("Mi 6", "Xiaomi"), skin("Nexus 5", "google")))
        assertEquals(listOf("google", "Samsung", "Sony", "Xiaomi", null), groups.map { it.first })
    }

    @Test fun skinsOfABrandAreInNaturalNameOrder() {
        val list = listOf("Galaxy S10", "Galaxy S4", "Galaxy S4 Mini", "Galaxy S5").map { skin(it, "Samsung") }
        assertEquals(listOf("Galaxy S4", "Galaxy S4 Mini", "Galaxy S5", "Galaxy S10"), VmThemes.skinsByBrand(list).single().second.map { it.name })
    }

    @Test fun blankBrandCountsAsNone() {
        assertEquals(listOf<String?>(null), VmThemes.skinsByBrand(listOf(skin("A", "  "))).map { it.first })
    }

    @Test fun cleanBrandTrimsAndLimits() {
        assertEquals(null, VmTheme.cleanBrand("  "))
        assertEquals("Sony", VmTheme.cleanBrand(" Sony\n"))
        assertTrue(VmTheme.cleanBrand("x".repeat(100))!!.length == VmTheme.BRAND_MAX)
    }
}
