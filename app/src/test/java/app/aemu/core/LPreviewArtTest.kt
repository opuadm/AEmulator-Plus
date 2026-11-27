package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class LPreviewArtTest {
    @Test fun aau70IsAnLPreview() = assertTrue(LPreviewArt.appliesTo("L", 19, hasLibart = true, hasDex2oat = true))
    @Test fun lowerCaseAndPaddedReleaseStillMatches() = assertTrue(LPreviewArt.appliesTo(" l ", 20, true, true))
    @Test fun releasedFourFourIsNot() = assertFalse(LPreviewArt.appliesTo("4.4.4", 19, true, true))
    @Test fun fourFourWIsNot() = assertFalse(LPreviewArt.appliesTo("4.4W", 20, true, true))
    @Test fun lollipopProperIsNot() = assertFalse(LPreviewArt.appliesTo("5.0", 21, true, true))
    @Test fun needsArtAndDex2oat() {
        assertFalse(LPreviewArt.appliesTo("L", 19, hasLibart = false, hasDex2oat = true))
        assertFalse(LPreviewArt.appliesTo("L", 19, hasLibart = true, hasDex2oat = false))
    }
    @Test fun releaseLabelAloneDecidesTheSettingsToggle() {
        assertTrue(LPreviewArt.releaseMatches("L", 19))
        assertFalse(LPreviewArt.releaseMatches("L", 21))
        assertFalse(LPreviewArt.releaseMatches("4.4", 19))
    }
}
