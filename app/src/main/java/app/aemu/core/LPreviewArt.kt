/* AEmulator Plus addition, 2026-11-24. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * Android "L" preview builds that run ART without a pre-built boot image (zygote then runs dex2oat on first boot under
 * qemu). Two are known: LPW39 (reports API 20) and AAU70 (AOSP x86 "IA Emulator", reports API 19, ro.build.version.release
 * "L", libdvm.so and libart.so both present, ART picked by persist.sys.dalvik.vm.lib.1).
 *
 * They share the failure: dex2oat's image-class initialisation runs framework code in an un-started runtime and aborts
 * (SQLiteDebug.<clinit> on LPW39, WebViewFactory$Preloader.<clinit> -> Class.forName(WebViewChromiumFactoryProvider) on AAU70),
 * so GenerateImage fails and every app_process that starts the runtime aborts with 134. Only the release string "L" with an
 * API below 21 identifies them: no released 4.4 / 4.4W reports "L".
 */
object LPreviewArt {
    /** The label alone (release "L", API 19-20): enough for the settings screen, which has no tree to look at. */
    fun releaseMatches(release: String, api: Int): Boolean =
        release.trim().equals("L", ignoreCase = true) && api in 19..20

    fun appliesTo(release: String, api: Int, hasLibart: Boolean, hasDex2oat: Boolean): Boolean =
        releaseMatches(release, api) && hasLibart && hasDex2oat
}
