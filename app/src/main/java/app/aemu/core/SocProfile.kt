/* Added for AEmulator Plus, 2026-11-19: device specification (SoC) spoofing. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.json.JSONArray
import org.json.JSONObject

/** A group of identical cores. Cores are numbered cluster after cluster, little cores first, as the kernel of a big.LITTLE SoC does. */
data class SocCluster(
    val count: Int,
    /** "CPU implementer" of /proc/cpuinfo: 0x41 ARM, 0x51 Qualcomm... */
    val implementer: Int,
    val variant: Int,
    /** "CPU part": 0xc0f Cortex-A15, 0xd05 Cortex-A55... */
    val part: Int,
    val revision: Int,
    val maxMhz: Int,
    val minMhz: Int = 300,
) {
    fun toJson(): JSONObject = JSONObject().put("count", count).put("implementer", implementer).put("variant", variant)
        .put("part", part).put("revision", revision).put("maxMhz", maxMhz).put("minMhz", minMhz)

    companion object {
        fun fromJson(o: JSONObject): SocCluster = SocCluster(
            count = o.optInt("count", 1).coerceIn(1, SocProfiles.MAX_CORES),
            implementer = o.optInt("implementer", 0x41).coerceIn(0, 0xff),
            variant = o.optInt("variant", 0).coerceIn(0, 0xf),
            part = o.optInt("part", 0).coerceIn(0, 0xfff),
            revision = o.optInt("revision", 0).coerceIn(0, 0xf),
            maxMhz = o.optInt("maxMhz", 1800).coerceIn(100, 6000),
            minMhz = o.optInt("minMhz", 300).coerceIn(100, 6000),
        )
    }
}

/**
 * Everything a guest can learn about the chip it runs on. The same record feeds /proc/cpuinfo, /sys/devices/soc0/
 * and the properties (ro.board.platform on every release, ro.soc.model / ro.soc.manufacturer from Android 12).
 * A blank text field means "do not report this one".
 */
data class SocSpec(
    val id: String,
    /** shown in the settings; not given to the guest */
    val label: String,
    /** ro.soc.manufacturer (Android 12+) */
    val manufacturer: String = "",
    /** ro.soc.model (Android 12+) and ro.hardware.chipname */
    val model: String = "",
    /** ro.board.platform */
    val platform: String = "",
    /** the "Hardware" line of /proc/cpuinfo */
    val hardware: String = "",
    /** /sys/devices/soc0/family */
    val family: String = "",
    /** /sys/devices/soc0/machine */
    val machine: String = "",
    /** /sys/devices/soc0/soc_id */
    val socId: String = "",
    /** /sys/devices/soc0/revision */
    val revision: String = "",
    /** /sys/devices/soc0/serial_number */
    val serialNumber: String = "",
    /** 7 = ARMv7 (32-bit) core, 8 = ARMv8 (64-bit) core; decides the layout of /proc/cpuinfo */
    val arch: Int = 8,
    val clusters: List<SocCluster> = listOf(SocCluster(4, 0x41, 0, 0xd03, 4, 1500)),
    /** the "Features" line; blank = the usual one of [arch] */
    val features: String = "",
    /** the "BogoMIPS" line; blank = the usual one of [arch] */
    val bogoMips: String = "",
    /** Added 2026-11-19. Instruction set of the chip: an x86 firmware is only offered x86 presets, an ARM one only ARM presets. */
    val isa: SocIsa = SocIsa.ARM,
    /** GL_VENDOR the guest's GLES driver reports while the phone's GPU is in use; blank = the phone's real one */
    val gpuVendor: String = "",
    /** GL_RENDERER the guest's GLES driver reports while the phone's GPU is in use; blank = the phone's real one */
    val gpuRenderer: String = "",
    /** x86 only: the /proc/cpuinfo block of an x86 core (see [X86Cpu]) */
    val x86: X86Cpu? = null,
    /** shown in the preset window only: process node and year, "28 nm · 2014" */
    val note: String = "",
    /**
     * Added 2026-11-19. Further vendor properties the real chip's firmware carries (MediaTek: ro.mediatek.platform and
     * friends). Never overrides the properties above; "ro.vendor.*" names only exist from Android 8 and are left out below that.
     */
    val extraProps: Map<String, String> = emptyMap(),
    /**
     * Added 2026-11-20. True for a chip whose reference kernel is 4.9 or older (3.x / 4.4 / 4.9): such a kernel prints the
     * "Processor : ... rev N" header line in /proc/cpuinfo and names soc0/chip_name (newer ones: chip_id).
     */
    val legacyKernel: Boolean = false,
    /** Added 2026-11-20. /sys/devices/soc0/hw_platform: the type of reference board ("MTP", "QRD", "HDK"...) */
    val hwPlatform: String = "MTP",
    /** Added 2026-11-20. /sys/devices/soc0/foundry_id (Qualcomm: 1 TSMC, 3 Samsung); -1 = not reported */
    val foundryId: Int = -1,
    /** Added 2026-11-20. Top GPU clock in MHz, for the kgsl nodes; 0 = none */
    val gpuMaxMhz: Int = 0,
    /** Added 2026-11-20. Further soc0 files of this chip (chip_family, nproduct_id, pmic_model...) that override the derived ones */
    val soc0Extra: Map<String, String> = emptyMap(),
) {
    val cores: Int get() = clusters.sumOf { it.count }.coerceIn(1, SocProfiles.MAX_CORES)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("manufacturer", manufacturer).put("model", model).put("platform", platform)
        .put("hardware", hardware).put("family", family).put("machine", machine).put("socId", socId).put("revision", revision)
        .put("serialNumber", serialNumber).put("arch", arch).put("features", features).put("bogoMips", bogoMips)
        .put("clusters", JSONArray(clusters.map { it.toJson() }))
        .put("isa", isa.id).put("gpuVendor", gpuVendor).put("gpuRenderer", gpuRenderer)
        .apply { x86?.let { put("x86", it.toJson()) } }
        .apply { if (extraProps.isNotEmpty()) put("extraProps", JSONObject(extraProps)) }
        .put("legacyKernel", legacyKernel).put("hwPlatform", hwPlatform).put("foundryId", foundryId).put("gpuMaxMhz", gpuMaxMhz)
        .apply { if (soc0Extra.isNotEmpty()) put("soc0Extra", JSONObject(soc0Extra)) }

    /** Printable single-line values of a sane length; property names built from them stay valid. */
    fun sanitized(): SocSpec = copy(
        id = SocProfiles.CUSTOM,
        label = line(label, 48).ifBlank { "Custom" },
        manufacturer = line(manufacturer, 32), model = line(model, 32),
        platform = token(platform, 32), hardware = line(hardware, 64),
        family = line(family, 32), machine = line(machine, 32), socId = line(socId, 16),
        revision = line(revision, 16), serialNumber = line(serialNumber, 24),
        arch = if (arch == 7) 7 else 8,
        features = line(features, 400), bogoMips = line(bogoMips, 12),
        gpuVendor = line(gpuVendor, 64), gpuRenderer = line(gpuRenderer, 96),
        x86 = if (isa == SocIsa.X86) x86?.sanitized() else null,
        clusters = clusters.take(4).ifEmpty { listOf(SocCluster(1, 0x41, 0, 0xd03, 0, 1500)) },
        extraProps = cleanProps(extraProps),
        hwPlatform = line(hwPlatform, 16).ifBlank { "MTP" }, foundryId = foundryId.coerceIn(-1, 15), gpuMaxMhz = gpuMaxMhz.coerceIn(0, 4000),
        soc0Extra = cleanProps(soc0Extra).filterKeys { it.all { c -> c.isLetterOrDigit() || c == '_' } },
    )

    companion object {
        /** Printable ASCII on one line: what a sysfs node and a property value may hold. */
        fun line(v: String, max: Int): String = v.filter { it in ' '..'~' }.trim().take(max)
        /** Property-safe: letters, digits and _ - . only (ro.board.platform names HAL libraries). */
        fun token(v: String, max: Int): String = v.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' || it == '.' }.take(max)

        /** Property names and values the guest's property area can hold (a value is at most 91 characters); at most 64 of them. */
        fun cleanProps(m: Map<String, String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in m) {
                val key = token(k, 64)
                val value = line(v, 91)
                if (key.isNotEmpty() && value.isNotEmpty() && out.size < 64) out[key] = value
            }
            return out
        }

        fun fromJson(o: JSONObject?): SocSpec? {
            if (o == null) return null
            val cl = o.optJSONArray("clusters")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(SocCluster::fromJson) } }.orEmpty()
            return SocSpec(
                id = SocProfiles.CUSTOM, label = o.optString("label", "Custom"),
                manufacturer = o.optString("manufacturer", ""), model = o.optString("model", ""), platform = o.optString("platform", ""),
                hardware = o.optString("hardware", ""), family = o.optString("family", ""), machine = o.optString("machine", ""),
                socId = o.optString("socId", ""), revision = o.optString("revision", ""), serialNumber = o.optString("serialNumber", ""),
                arch = o.optInt("arch", 8), clusters = cl, features = o.optString("features", ""), bogoMips = o.optString("bogoMips", ""),
                isa = SocIsa.of(o.optString("isa", "arm")), gpuVendor = o.optString("gpuVendor", ""), gpuRenderer = o.optString("gpuRenderer", ""),
                x86 = X86Cpu.fromJson(o.optJSONObject("x86")),
                extraProps = o.optJSONObject("extraProps")?.let { e -> e.keys().asSequence().associateWith { e.optString(it, "") } }.orEmpty(),
                legacyKernel = o.optBoolean("legacyKernel", false), hwPlatform = o.optString("hwPlatform", "MTP"),
                foundryId = o.optInt("foundryId", -1), gpuMaxMhz = o.optInt("gpuMaxMhz", 0),
                soc0Extra = o.optJSONObject("soc0Extra")?.let { e -> e.keys().asSequence().associateWith { e.optString(it, "") } }.orEmpty(),
            ).sanitized()
        }
    }
}

/** Instruction set family of a chip. x86 and x86_64 firmware share the x86 presets. */
enum class SocIsa(val id: String) {
    ARM("arm"), X86("x86");
    companion object { fun of(id: String) = if (id.equals("x86", true)) X86 else ARM }
}

/**
 * The /proc/cpuinfo block of one x86 core, plus what the same chip's kernel shows in /sys (microcode, caches, idle states,
 * cpufreq driver, thermal, PCI graphics, DMI). The core count and clocks come from [SocSpec.clusters] (one logical cpu per thread).
 */
data class X86Cpu(
    val vendorId: String = "GenuineIntel",
    val family: Int = 6,
    val model: Int = 0,
    val stepping: Int = 0,
    /** the brand string, "Intel(R) Atom(TM) CPU Z3560 @ 1.83GHz" */
    val modelName: String = "",
    /** the "cache size" line: the last-level cache one core sees (Atom: the L2 of its module) */
    val cacheKb: Int = 1024,
    /** cores / threads per core, for "siblings" and "cpu cores" */
    val physicalCores: Int = 1,
    val flags: String = "",
    val physBits: Int = 36,
    val virtBits: Int = 48,
    /** Added 2026-11-20. The "microcode" line and cpuN/microcode/version, "0x38"; blank = no such line */
    val microcode: String = "",
    /** Added 2026-11-20. The "cpuid level" line: highest basic CPUID leaf (Saltwell 10, Silvermont / Airmont 11, Comet Lake 22) */
    val cpuidLevel: Int = 11,
    /** Added 2026-11-20. The "bugs" line (kernel 4.9 and newer print it); blank = a kernel without it */
    val bugs: String = "",
    /** Added 2026-11-20. The "vmx flags" line (kernel 4.14 and newer); blank = none */
    val vmxFlags: String = "",
    /** Added 2026-11-20. "saltwell", "silvermont", "airmont" or "desktop": decides caches, idle states, vulnerabilities; blank = generic */
    val uarch: String = "",
    /** Added 2026-11-20. scaling_driver: "intel_pstate", "sfi-cpufreq", "acpi-cpufreq"; blank = leave to the engine */
    val cpufreqDriver: String = "",
    /** Added 2026-11-20. PCI "vendor:device" of the graphics device under /sys/class/drm/card0/device ("0x8086:0x0f31"); blank = none (SoC GPU) */
    val gpuPci: String = "",
    /** Added 2026-11-20. /sys/class/dmi/id of a UEFI / BIOS board; null = no DMI (a phone-class Atom: SFI / device tree boot) */
    val dmi: X86Dmi? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("vendorId", vendorId).put("family", family).put("model", model).put("stepping", stepping)
        .put("modelName", modelName).put("cacheKb", cacheKb).put("physicalCores", physicalCores).put("flags", flags)
        .put("physBits", physBits).put("virtBits", virtBits).put("microcode", microcode).put("cpuidLevel", cpuidLevel)
        .put("bugs", bugs).put("vmxFlags", vmxFlags).put("uarch", uarch).put("cpufreqDriver", cpufreqDriver).put("gpuPci", gpuPci)
        .apply { dmi?.let { put("dmi", it.toJson()) } }

    fun sanitized(): X86Cpu = copy(
        vendorId = SocSpec.line(vendorId, 16).ifBlank { "GenuineIntel" }, modelName = SocSpec.line(modelName, 80),
        family = family.coerceIn(0, 255), model = model.coerceIn(0, 255), stepping = stepping.coerceIn(0, 15),
        cacheKb = cacheKb.coerceIn(0, 1 shl 20), physicalCores = physicalCores.coerceIn(1, SocProfiles.MAX_CORES),
        flags = SocSpec.line(flags, 800), physBits = physBits.coerceIn(32, 52), virtBits = virtBits.coerceIn(32, 57),
        microcode = SocSpec.line(microcode, 12).filter { it.isLetterOrDigit() }.let { if (it.isEmpty()) "" else if (it.startsWith("0x")) it else "0x$it" },
        cpuidLevel = cpuidLevel.coerceIn(1, 64), bugs = SocSpec.line(bugs, 200), vmxFlags = SocSpec.line(vmxFlags, 400),
        uarch = SocSpec.token(uarch, 16).lowercase(), cpufreqDriver = SocSpec.token(cpufreqDriver, 24),
        gpuPci = SocSpec.line(gpuPci, 16).takeIf { Regex("0x[0-9a-fA-F]{4}:0x[0-9a-fA-F]{4}").matches(it) }.orEmpty(),
        dmi = dmi?.sanitized(),
    )

    companion object {
        fun fromJson(o: JSONObject?): X86Cpu? = o?.let {
            X86Cpu(
                vendorId = it.optString("vendorId", "GenuineIntel"), family = it.optInt("family", 6), model = it.optInt("model", 0),
                stepping = it.optInt("stepping", 0), modelName = it.optString("modelName", ""), cacheKb = it.optInt("cacheKb", 1024),
                physicalCores = it.optInt("physicalCores", 1), flags = it.optString("flags", ""), physBits = it.optInt("physBits", 36),
                virtBits = it.optInt("virtBits", 48), microcode = it.optString("microcode", ""), cpuidLevel = it.optInt("cpuidLevel", 11),
                bugs = it.optString("bugs", ""), vmxFlags = it.optString("vmxFlags", ""), uarch = it.optString("uarch", ""),
                cpufreqDriver = it.optString("cpufreqDriver", ""), gpuPci = it.optString("gpuPci", ""), dmi = X86Dmi.fromJson(it.optJSONObject("dmi")),
            )
        }
    }
}

/**
 * Added 2026-11-20. The DMI (SMBIOS) strings of /sys/class/dmi/id on an x86 board. Serial numbers and the UUID are zeros: they
 * identify one machine and nothing here is one.
 */
data class X86Dmi(
    val sysVendor: String, val productName: String, val productVersion: String = "", val productFamily: String = "", val productSku: String = "",
    val boardVendor: String = "", val boardName: String = "", val boardVersion: String = "",
    val biosVendor: String = "", val biosVersion: String = "", val biosDate: String = "", val biosRelease: String = "",
    val chassisVendor: String = "", val chassisVersion: String = "", val chassisType: Int = 3,
) {
    fun toJson(): JSONObject = JSONObject().put("sysVendor", sysVendor).put("productName", productName).put("productVersion", productVersion)
        .put("productFamily", productFamily).put("productSku", productSku).put("boardVendor", boardVendor).put("boardName", boardName)
        .put("boardVersion", boardVersion).put("biosVendor", biosVendor).put("biosVersion", biosVersion).put("biosDate", biosDate)
        .put("biosRelease", biosRelease).put("chassisVendor", chassisVendor).put("chassisVersion", chassisVersion).put("chassisType", chassisType)

    fun sanitized(): X86Dmi = X86Dmi(
        SocSpec.line(sysVendor, 48), SocSpec.line(productName, 48), SocSpec.line(productVersion, 48), SocSpec.line(productFamily, 48), SocSpec.line(productSku, 48),
        SocSpec.line(boardVendor, 48), SocSpec.line(boardName, 48), SocSpec.line(boardVersion, 48),
        SocSpec.line(biosVendor, 48), SocSpec.line(biosVersion, 48), SocSpec.line(biosDate, 10), SocSpec.line(biosRelease, 8),
        SocSpec.line(chassisVendor, 48), SocSpec.line(chassisVersion, 48), chassisType.coerceIn(1, 36),
    )

    companion object {
        fun fromJson(o: JSONObject?): X86Dmi? = o?.let {
            X86Dmi(
                it.optString("sysVendor", ""), it.optString("productName", ""), it.optString("productVersion", ""), it.optString("productFamily", ""),
                it.optString("productSku", ""), it.optString("boardVendor", ""), it.optString("boardName", ""), it.optString("boardVersion", ""),
                it.optString("biosVendor", ""), it.optString("biosVersion", ""), it.optString("biosDate", ""), it.optString("biosRelease", ""),
                it.optString("chassisVendor", ""), it.optString("chassisVersion", ""), it.optInt("chassisType", 3),
            )
        }
    }
}

/** What the host phone says about itself; [app.aemu.core.SocHost] reads it from the real device, tests supply their own. */
interface HostSocSource {
    fun cpuinfo(): String?
    /** a file of the phone's /sys/devices/soc0 */
    fun soc0(name: String): String?
    fun prop(name: String): String?
    /** (min, max) in kHz of the phone's cpu [n], when it can be read */
    fun cpuFreqKhz(n: Int): Pair<Int, Int>?
    fun cpuCount(): Int
}

/** The values one VM start puts into the guest. */
class SocResolved(
    val id: String,
    val label: String,
    val cpuinfo: String,
    /** /sys/devices/soc0/<name> -> text; names the profile does not set are absent */
    val soc0: Map<String, String>,
    /** property -> value, already limited to what the firmware's Android version knows */
    val props: Map<String, String>,
    val cpuCount: Int,
    /** (min, max) kHz of each cpu, indexed by cpu number */
    val cpuFreqs: List<Pair<Int, Int>>,
    /** GL_VENDOR / GL_RENDERER given to the guest while the phone's GPU renders; blank = the phone's real ones (the Host chip, or a row without GPU) */
    val gpuVendor: String = "",
    val gpuRenderer: String = "",
    /**
     * Added 2026-11-20. Further files of the guest (absolute path -> content, trailing newline included) that the chip's kernel
     * shows: cpu MIDR / modalias, kgsl, MediaTek cputopo, device-tree model. Empty for the Host chip. The VM start has to create them.
     */
    val files: Map<String, String> = emptyMap(),
)

object SocProfiles {
    /** The setting's blank value: Yellowstone on a Project Tango image, the host's chip on every other. */
    const val DEFAULT = ""
    const val HOST = "host"
    const val CUSTOM = "custom"
    const val YELLOWSTONE = "yellowstone"
    const val MAX_CORES = 16

    /** Features lines. A 64-bit kernel shows a 32-bit task the ARMv7 flags plus the crypto/crc ones the core has. */
    private const val V7 = "half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm"
    private val COMPAT_EXTRA = listOf("aes", "pmull", "sha1", "sha2", "crc32")
    private const val A64_BASE = "fp asimd evtstrm aes pmull sha1 sha2 crc32"
    private const val A64_LITE = "fp asimd evtstrm crc32"
    private const val A64_V82 = "$A64_BASE atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private const val A64_V9 = "$A64_V82 sha3 sm3 sm4 sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svei8mm svebf16 i8mm bf16 dgh bti"
    private const val A64_ORYON = "$A64_V82 jscvt fcma sha3 sm3 sm4 sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp flagm2 frint i8mm bf16 dgh bti"

    /** The chip of the Project Tango tablet (Tegra K1, "Yellowstone"): what a Tango image reports unless another chip is chosen. */
    val YELLOWSTONE_SPEC = SocSpec(
        id = YELLOWSTONE, label = "NVIDIA Tegra K1 (Yellowstone)",
        hardware = "Yellowstone", family = "Tegra", machine = "Tegra K1", socId = "53", revision = "A02", serialNumber = "0",
        arch = 7, clusters = listOf(SocCluster(4, 0x41, 2, 0xc0f, 3, 2300, 204)), features = V7, bogoMips = "38.40",
    )

    /** Every preset, ARM and x86; the rows live in [SocPresetTable]. */
    val presets: List<SocSpec> = SocPresetTable.arm + SocPresetTable.x86

    /** The presets a firmware of this instruction set may be given: an x86 image sees no ARM chip and an ARM image no x86 one. */
    fun presetsFor(isa: SocIsa): List<SocSpec> = presets.filter { it.isa == isa }

    /** The instruction set of a firmware's architecture. */
    fun isaOf(arch: GuestArch): SocIsa = if (arch == GuestArch.ARM) SocIsa.ARM else SocIsa.X86

    /** True for an id the setting can hold: a preset, the host or the custom chip. */
    fun valid(id: String): Boolean = id == HOST || id == CUSTOM || presets.any { it.id == id }

    fun clean(id: String): String = id.trim().lowercase().takeIf { valid(it) } ?: DEFAULT

    fun preset(id: String): SocSpec? = presets.firstOrNull { it.id == id }

    /** The id that is really used: the setting's own, or the default of the image. */
    fun effectiveId(setting: String, tango: Boolean, isa: SocIsa = SocIsa.ARM): String {
        val id = clean(setting)
        // (a chip of the other instruction set is ignored: the image then reports its own default)
        if (id != HOST && id != CUSTOM && id.isNotEmpty() && preset(id)?.isa != isa) return if (tango) YELLOWSTONE else HOST
        return id.ifEmpty { if (tango) YELLOWSTONE else HOST }
    }

    /** Name for the settings list. */
    fun label(id: String, custom: SocSpec?): String = when (id) {
        HOST -> "Host"
        CUSTOM -> custom?.label ?: "Custom"
        YELLOWSTONE -> YELLOWSTONE_SPEC.label
        else -> preset(id)?.label ?: id
    }

    /** True for the Project Tango tablet's firmware (Tegra K1, device "yellowstone"), found by its build properties. */
    fun isTango(props: Map<String, String>): Boolean {
        val keys = listOf("ro.product.device", "ro.product.name", "ro.product.board", "ro.hardware", "ro.build.product")
        if (keys.any { props[it].equals("yellowstone", true) }) return true
        return props["ro.product.model"]?.contains("Project Tango", true) == true &&
            props["ro.product.model"]?.contains("Tablet", true) == true
    }

    /** [isTango] over the build properties of an unpacked firmware tree (and the library's own model / name of the image). */
    fun isTangoTree(root: java.io.File, model: String = "", name: String = ""): Boolean {
        if (listOf(model, name).any { it.contains("yellowstone", true) }) return true
        val props = LinkedHashMap<String, String>()
        for (n in listOf("system/build.prop", "vendor/build.prop", "system/vendor/build.prop", "default.prop", "system/default.prop", "vendor/default.prop"))
            runCatching { java.io.File(root, n).takeIf { it.isFile && it.length() < 1_000_000 }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> props.putIfAbsent(k, v) } } }
        return isTango(props)
    }

    // ---- cpuinfo --------------------------------------------------------------------------------------------------

    private fun hex(v: Int, width: Int = 0) = "0x" + Integer.toHexString(v).padStart(width, '0')

    /**
     * /proc/cpuinfo of [spec]. [guest32]: the firmware's programs are 32-bit ARM, which a 64-bit kernel serves in the
     * ARMv7-compatible layout ("model name", "Features: ... neon vfpv4 ..."); a 64-bit firmware gets the AArch64 layout.
     */
    fun cpuinfo(spec: SocSpec, guest32: Boolean): String {
        if (spec.isa == SocIsa.X86 && spec.x86 != null) return x86Cpuinfo(spec)
        val v8 = spec.arch >= 8
        val aarch64 = v8 && !guest32
        val features = when {
            !v8 -> spec.features.ifBlank { V7 }
            aarch64 -> spec.features.ifBlank { A64_BASE }
            else -> compatFeatures(spec.features.ifBlank { A64_BASE })
        }
        val bogo = spec.bogoMips.ifBlank { "38.40" }
        return buildString {
            // kernels up to 4.9 print one summary line first ("Processor : AArch64 Processor rev 1 (aarch64)")
            if (spec.legacyKernel && (aarch64 || !v8))
                append("Processor\t: ").append(if (aarch64) "AArch64" else "ARMv7").append(" Processor rev ")
                    .append(spec.clusters.first().revision).append(if (aarch64) " (aarch64)" else " (v7l)").append('\n')
            var n = 0
            for (c in spec.clusters) repeat(c.count) {
                if (n >= MAX_CORES) return@repeat
                append("processor\t: ").append(n++).append('\n')
                if (!aarch64) append("model name\t: ARMv").append(if (v8) "8" else "7").append(" Processor rev ").append(c.revision)
                    .append(if (v8) " (v8l)" else " (v7l)").append('\n')
                append("BogoMIPS\t: ").append(bogo).append('\n')
                append("Features\t: ").append(features).append('\n')
                append("CPU implementer\t: ").append(hex(c.implementer, 2)).append('\n')
                append("CPU architecture: ").append(if (v8) 8 else 7).append('\n')
                append("CPU variant\t: ").append(hex(c.variant)).append('\n')
                append("CPU part\t: ").append(hex(c.part, 3)).append('\n')
                append("CPU revision\t: ").append(c.revision).append("\n\n")
            }
            append("Hardware\t: ").append(spec.hardware.ifBlank { spec.model.ifBlank { "unknown" } }).append('\n')
            // an ARMv7 kernel also prints Revision and Serial; an AArch64 kernel ends the file with the Hardware line
            if (!v8) append("Revision\t: 0000\nSerial\t\t: ").append(serialHex(spec.serialNumber)).append('\n')
        }
    }

    /** The Serial line of an ARMv7 /proc/cpuinfo: the 32-bit soc0 serial number as hex, 16 digits. */
    private fun serialHex(serial: String): String =
        serial.toLongOrNull()?.let { String.format("%08x", it and 0xffffffffL) + "00000000" } ?: "0000000000000000"

    /**
     * /proc/cpuinfo of an x86 chip, in the layout of a Linux x86 kernel: one block per logical cpu, no Hardware line. Line order is
     * the kernel's (arch/x86/kernel/cpu/proc.c): microcode after stepping, "vmx flags" and "bugs" between flags and bogomips.
     * Logical cpus are numbered the way Intel kernels do: thread 0 of every core first, then the sibling threads (cpu0 and cpu6 share
     * core 0 on a 6-core / 12-thread chip); apicid is 2 * core + thread on a hyper-threaded chip and 2 * core on a Silvermont
     * module chip (the zenfone-2 dump: 0, 2, 4, 6).
     */
    fun x86Cpuinfo(spec: SocSpec): String {
        val x = spec.x86 ?: return ""
        val total = spec.cores
        val cores = x.physicalCores.coerceIn(1, total)
        val perCore = (total / cores).coerceAtLeast(1)
        val mhz = spec.clusters.firstOrNull()?.maxMhz ?: 1000
        // base clock for "cpu MHz": the one of the brand string ("@ 1.83GHz"), else the top clock
        val base = Regex("@\\s*([0-9.]+)\\s*GHz").find(x.modelName)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toInt() } ?: mhz
        val bogo = "%.2f".format(base * 2.0)
        return buildString {
            for (n in 0 until total) {
                val core = n % cores
                val thread = n / cores
                val apic = if (perCore == 1) core * x86ApicStride(x) else core * 2 + thread
                append("processor\t: ").append(n).append('\n')
                append("vendor_id\t: ").append(x.vendorId).append('\n')
                append("cpu family\t: ").append(x.family).append('\n')
                append("model\t\t: ").append(x.model).append('\n')
                append("model name\t: ").append(x.modelName).append('\n')
                append("stepping\t: ").append(x.stepping).append('\n')
                if (x.microcode.isNotBlank()) append("microcode\t: ").append(x.microcode).append('\n')
                append("cpu MHz\t\t: ").append("%.3f".format(base.toDouble())).append('\n')
                append("cache size\t: ").append(x.cacheKb).append(" KB\n")
                append("physical id\t: 0\nsiblings\t: ").append(total).append('\n')
                append("core id\t\t: ").append(core).append('\n')
                append("cpu cores\t: ").append(cores).append('\n')
                append("apicid\t\t: ").append(apic).append("\ninitial apicid\t: ").append(apic).append('\n')
                append("fpu\t\t: yes\nfpu_exception\t: yes\ncpuid level\t: ").append(x.cpuidLevel).append("\nwp\t\t: yes\n")
                append("flags\t\t: ").append(x.flags).append('\n')
                if (x.vmxFlags.isNotBlank()) append("vmx flags\t: ").append(x.vmxFlags).append('\n')
                if (x.bugs.isNotBlank()) append("bugs\t\t: ").append(x.bugs).append('\n')
                append("bogomips\t: ").append(bogo).append('\n')
                append("clflush size\t: 64\ncache_alignment\t: 64\n")
                append("address sizes\t: ").append(x.physBits).append(" bits physical, ").append(x.virtBits).append(" bits virtual\n")
                append("power management:\n\n")
            }
        }
    }

    /** Silvermont-family chips (cpuid models 0x37 / 0x4a / 0x4c / 0x5a / 0x5d) put two APIC ids between cores. */
    private fun x86ApicStride(x: X86Cpu): Int = if (x.family == 6 && x.model in setOf(55, 74, 76, 90, 93)) 2 else 1

    /** What an AArch32 task sees of an AArch64 core's feature list. */
    fun compatFeatures(a64: String): String {
        val have = a64.split(' ').filter { it.isNotEmpty() }.toSet()
        return (V7.split(' ') + COMPAT_EXTRA.filter { it in have }).joinToString(" ")
    }

    /**
     * The phone's own /proc/cpuinfo for the guest: the same cores, so apps size their pools right, with a Hardware line
     * (Tango's Settings needs one, and an arm64 kernel often prints none), a zeroed Serial and, for a 32-bit firmware,
     * the layout a 64-bit kernel gives a 32-bit task instead of the AArch64 "asimd" one.
     */
    fun hostCpuinfo(text: String, guest32: Boolean, chip: String): String {
        val blocks = text.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }
        val cores = ArrayList<String>()
        val tail = ArrayList<String>()
        for (b in blocks) {
            val lines = b.split('\n')
            if (lines.any { it.startsWith("processor") }) cores += b else tail += lines
        }
        val out = StringBuilder()
        for (b in cores) {
            var lines = b.split('\n')
            val features = lines.firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim().orEmpty()
            if (guest32 && "asimd" in features.split(' ')) {
                val rev = lines.firstOrNull { it.startsWith("CPU revision") }?.substringAfter(':')?.trim()?.toIntOrNull(16) ?: 0
                val res = ArrayList<String>()
                for (l in lines) {
                    res += when {
                        l.startsWith("Features") -> "Features\t: " + compatFeatures(features)
                        else -> l
                    }
                    if (l.startsWith("processor") && lines.none { it.startsWith("model name") }) res += "model name\t: ARMv8 Processor rev $rev (v8l)"
                }
                lines = res
            }
            out.append(lines.joinToString("\n")).append("\n\n")
        }
        var hw = tail.firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim().orEmpty()
        if (hw.isEmpty()) hw = chip.ifBlank { "unknown" }
        out.append("Hardware\t: ").append(hw).append('\n')
        out.append("Revision\t: ").append(tail.firstOrNull { it.startsWith("Revision") }?.substringAfter(':')?.trim()?.ifEmpty { null } ?: "0000").append('\n')
        out.append("Serial\t\t: 0000000000000000\n")   // never the phone's own serial
        return out.toString()
    }

    // ---- soc0 and properties --------------------------------------------------------------------------------------

    /**
     * /sys/devices/soc0/. Every Qualcomm chip gets the node set a Qualcomm kernel registers (checked against the soc0 directory of
     * real Snapdragon 835 / 845 phones); the values that tell one board from another are neutral reference-board ones.
     */
    fun soc0Files(spec: SocSpec): Map<String, String> {
        if (spec.isa == SocIsa.X86) return emptyMap()
        val m = linkedMapOf(
            "family" to spec.family, "machine" to spec.machine.ifBlank { spec.model }, "soc_id" to spec.socId,
            "revision" to spec.revision, "serial_number" to spec.serialNumber,
        )
        if (spec.manufacturer == "QTI") {
            val chip = spec.model.substringBefore('-')
            m["vendor"] = "Qualcomm"
            m[if (spec.legacyKernel) "chip_name" else "chip_id"] = chip
            m["hw_platform"] = spec.hwPlatform
            m["platform_version"] = "65536"
            m["platform_subtype_id"] = "0"
            m["platform_subtype"] = "Unknown"
            m["accessory_chip"] = "0"
            // the raw die id and version: any plausible number, fixed per chip
            m["raw_id"] = (50 + (spec.id.hashCode().toLong() and 0x7fffffffL) % 900).toString()
            m["raw_version"] = "2"
            if (spec.foundryId >= 0) m["foundry_id"] = spec.foundryId.toString()
            m["raw_device_family"] = "0x6"
            m["raw_device_number"] = "0x0"
            m["nmodem_supported"] = "0xff"
            m["num_clusters"] = "0x1"
            if (spec.legacyKernel) {
                m["ncluster_array_offset"] = "0xb0"; m["ndefective_parts_array_offset"] = "0xb4"; m["num_defective_parts"] = "0x6"
            } else {
                m["sku"] = "Unknown"; m["feature_code"] = "Unknown"; m["pcode"] = "0x0"
            }
            m["select_image"] = "10"
        }
        m.putAll(spec.soc0Extra)
        return m.filterValues { it.isNotBlank() }
    }

    /** The three-digit Adreno model of a GL_RENDERER ("Adreno (TM) 640" -> 640), null for any other GPU. */
    private fun adrenoNumber(renderer: String): Int? =
        if (renderer.startsWith("Adreno")) Regex("(\\d{3})").find(renderer)?.value?.toIntOrNull() else null

    /** ro.opengles.version of a chip: GLES 3.2 (196610), 3.1, 3.0 or 2.0. */
    private fun glesVersion(spec: SocSpec): Int {
        val n = adrenoNumber(spec.gpuRenderer)
        return when {
            n != null -> if (n >= 500) 196610 else if (n >= 400) 196609 else if (n >= 300) 196608 else 131072
            spec.gpuRenderer.startsWith("Mali-4") -> 131072
            else -> 196610
        }
    }

    /**
     * ro.board.platform on every release; ro.soc.model and ro.soc.manufacturer from Android 12 (API 31). On top of those the
     * properties a chip's reference firmware carries: Qualcomm (ro.hardware=qcom, the Adreno GL / Vulkan driver names) and
     * MediaTek (ro.mediatek.*, ro.vendor.mediatek.*, mediatek.wlan.*), as seen in build.prop / getprop of real phones.
     */
    fun props(spec: SocSpec, api: Int): Map<String, String> = linkedMapOf<String, String>().apply {
        if (spec.platform.isNotBlank()) put("ro.board.platform", spec.platform)
        if (spec.model.isNotBlank()) put("ro.hardware.chipname", spec.model)
        if (api >= 31) {
            if (spec.manufacturer.isNotBlank()) put("ro.soc.manufacturer", spec.manufacturer)
            if (spec.model.isNotBlank()) put("ro.soc.model", spec.model)
        }
        if (spec.isa == SocIsa.ARM) when (spec.manufacturer) {
            "QTI" -> {
                put("ro.hardware", "qcom"); put("ro.boot.hardware", "qcom")
                if (spec.platform.isNotBlank()) put("ro.product.board", spec.platform)
                if (spec.gpuRenderer.startsWith("Adreno")) {
                    put("ro.hardware.egl", "adreno")
                    if ((adrenoNumber(spec.gpuRenderer) ?: 0) >= 500) put("ro.hardware.vulkan", "adreno")
                }
                put("ro.opengles.version", glesVersion(spec).toString())
            }
            "MediaTek" -> {
                val chip = spec.model.uppercase()
                val low = spec.platform.ifBlank { chip.lowercase() }
                put("ro.hardware", low); put("ro.boot.hardware", low)
                put("ro.mediatek.platform", chip); put("ro.mediatek.chip_ver", "S01"); put("ro.mediatek.version.sdk", "4")
                put("mediatek.wlan.chip", "CONSYS_$chip"); put("mediatek.wlan.module.postfix", "_consys_" + chip.lowercase())
                if (api >= 26) put("ro.vendor.mediatek.platform", chip)
                put("ro.hardware.egl", "mali")
                if (spec.gpuRenderer.startsWith("Mali-G")) put("ro.hardware.vulkan", "mali")
                put("ro.opengles.version", glesVersion(spec).toString())
            }
        }
        for ((k, v) in spec.extraProps) if (api >= 26 || !k.startsWith("ro.vendor.")) if (k !in this) put(k, v)
    }

    // ---- other files of the guest ---------------------------------------------------------------------------------

    /** The ARM hwcap bit of each /proc/cpuinfo "Features" word (HWCAP and HWCAP2 of arm64, bit 32 on). */
    private val HWCAP64 = listOf(
        "fp", "asimd", "evtstrm", "aes", "pmull", "sha1", "sha2", "crc32", "atomics", "fphp", "asimdhp", "cpuid", "asimdrdm", "jscvt", "fcma",
        "lrcpc", "dcpop", "sha3", "sm3", "sm4", "asimddp", "sha512", "sve", "asimdfhm", "dit", "uscat", "ilrcpc", "flagm", "ssbs", "sb", "paca",
        "pacg", "dcpodp", "sve2", "sveaes", "svepmull", "svebitperm", "svesha3", "svesm4", "flagm2", "frint", "svei8mm", "svebf16", "i8mm",
        "bf16", "dgh", "rng", "bti", "mte",
    )

    /** The kgsl clock steps of a GPU whose top clock is [maxMhz] (the shape of the Adreno 630's seven steps: 710 .. 257 MHz). */
    private fun gpuSteps(maxMhz: Int): List<Int> = listOf(1.0, 0.95, 0.84, 0.73, 0.58, 0.48, 0.36).map { (maxMhz * it).toInt() }

    private fun cpuMask(from: Int, count: Int): String = java.lang.Long.toHexString(((1L shl count) - 1) shl from)

    /**
     * Files the chip's kernel shows besides cpuinfo / soc0 / the properties (see [SocResolved.files]): the cpu list, the
     * MIDR of every cpu and the hwcap modalias (arm64), the Adreno kgsl nodes, MediaTek's cputopo directory and the
     * device-tree model / compatible. Nothing here is an identifier of one phone.
     */
    fun files(spec: SocSpec): Map<String, String> {
        if (spec.isa == SocIsa.X86) return x86Files(spec)
        val out = linkedMapOf<String, String>()
        val n = spec.cores
        val cpu = "/sys/devices/system/cpu"
        out["$cpu/kernel_max"] = "${n - 1}\n"
        for (f in listOf("possible", "present", "online")) out["$cpu/$f"] = (if (n > 1) "0-${n - 1}" else "0") + "\n"
        out["$cpu/offline"] = "\n"
        if (spec.arch >= 8) {
            var i = 0
            for (c in spec.clusters) repeat(c.count) {
                if (i >= MAX_CORES) return@repeat
                val midr = (c.implementer.toLong() shl 24) or (c.variant.toLong() shl 20) or (0xfL shl 16) or (c.part.toLong() shl 4) or c.revision.toLong()
                out["$cpu/cpu$i/regs/identification/midr_el1"] = "0x" + midr.toString(16).padStart(16, '0') + "\n"
                i++
            }
            val have = spec.features.ifBlank { A64_BASE }.split(' ').filter { it.isNotEmpty() }.toSet()
            out["$cpu/modalias"] = "cpu:type:aarch64:feature:" +
                HWCAP64.withIndex().filter { it.value in have }.joinToString("") { ",%04X".format(it.index) } + "\n"
        }
        when (spec.manufacturer) {
            "QTI" -> {
                val a = adrenoNumber(spec.gpuRenderer)
                if (a != null && spec.gpuMaxMhz > 0) {
                    val k = "/sys/class/kgsl/kgsl-3d0"
                    val steps = gpuSteps(spec.gpuMaxMhz)
                    val model = "Adreno$a" + (if (a == 630) "v2" else "v1")
                    out["$k/gpu_model"] = "$model\n"; out["/sys/kernel/gpu/gpu_model"] = "$model\n"
                    out["$k/max_gpuclk"] = "${spec.gpuMaxMhz * 1_000_000L}\n"
                    out["$k/min_clock_mhz"] = "${steps.last()}\n"; out["$k/clock_mhz"] = "${steps.last()}\n"
                    out["$k/freq_table_mhz"] = steps.joinToString("") { "$it " } + "\n"
                    out["$k/gpu_available_frequencies"] = steps.joinToString("") { "${it * 1_000_000L} " } + "\n"
                    out["$k/num_pwrlevels"] = "${steps.size}\n"
                }
                val name = spec.model.substringBefore('-').lowercase()
                out["/proc/device-tree/model"] = "Qualcomm Technologies, Inc. ${spec.model.substringBefore('-')} V${spec.revision} ${spec.hwPlatform}\u0000"
                out["/proc/device-tree/compatible"] = "qcom,$name-${spec.hwPlatform.lowercase()}\u0000qcom,$name\u0000qcom,${spec.hwPlatform.lowercase()}\u0000"
            }
            "MediaTek" -> {
                // MediaTek's cputopo directory: one hex cpu mask per cluster
                val t = "$cpu/cputopo"
                var from = 0
                val masks = spec.clusters.map { c -> cpuMask(from, c.count).also { from += c.count } }
                val multi = masks.size > 1
                out["$t/cpus_per_cluster"] = masks.withIndex().joinToString("") { "cluster${it.index}: ${it.value}\n" }
                out["$t/glbinfo"] = "big/little arch: ${if (multi) "yes" else "no"}\nnr_cups: $n\nnr_clusters: ${masks.size}\n" +
                    masks.withIndex().joinToString("") { "cluster${it.index}: ${it.value}\n" }
                out["$t/is_big_little"] = (if (multi) "1" else "0") + "\n"
                out["$t/is_multi_cluster"] = (if (multi) "1" else "0") + "\n"
                out["$t/nr_clusters"] = "${masks.size}\n"
                out["/proc/device-tree/model"] = spec.model.uppercase() + "\u0000"
                out["/proc/device-tree/compatible"] = "mediatek,${spec.model.uppercase()}\u0000"
            }
        }
        // the same two nodes under /sys/firmware/devicetree/base (a symlink of /proc/device-tree on a real phone)
        for (f in listOf("model", "compatible")) out["/proc/device-tree/$f"]?.let { out["/sys/firmware/devicetree/base/$f"] = it }
        return out
    }


    // ---- x86: /sys and /proc files ---------------------------------------------------------------------------------

    private fun cpuList(cpus: List<Int>): String {
        if (cpus.isEmpty()) return ""
        val parts = ArrayList<String>()
        var start = cpus[0]; var prev = start
        for (c in cpus.drop(1) + (-99)) {
            if (c == prev + 1) { prev = c; continue }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = c; prev = c
        }
        return parts.joinToString(",")
    }

    private fun cpuMaskOf(cpus: List<Int>): String = java.lang.Long.toHexString(cpus.fold(0L) { m, c -> m or (1L shl c) })

    private class X86Cache(val level: Int, val type: String, val kb: Int, val ways: Int, val shared: (Int) -> List<Int>)

    private data class IdleState(val name: String, val desc: String, val latency: Int, val residency: Int)

    private val IDLE_SKL = listOf(IdleState("POLL", "CPUIDLE CORE POLL IDLE", 0, 0), IdleState("C1-SKL", "MWAIT 0x00", 2, 2), IdleState("C1E-SKL", "MWAIT 0x01", 10, 20),
        IdleState("C3-SKL", "MWAIT 0x10", 70, 100), IdleState("C6-SKL", "MWAIT 0x20", 85, 200), IdleState("C7s-SKL", "MWAIT 0x33", 124, 800),
        IdleState("C8-SKL", "MWAIT 0x40", 200, 800), IdleState("C9-SKL", "MWAIT 0x50", 480, 5000), IdleState("C10-SKL", "MWAIT 0x60", 890, 5000))
    private val IDLE_BYT = listOf(IdleState("POLL", "CPUIDLE CORE POLL IDLE", 0, 0), IdleState("C1-BYT", "MWAIT 0x00", 1, 1), IdleState("C6N-BYT", "MWAIT 0x58", 300, 275),
        IdleState("C6S-BYT", "MWAIT 0x52", 500, 560), IdleState("C7-BYT", "MWAIT 0x60", 1200, 4000), IdleState("C7S-BYT", "MWAIT 0x64", 10000, 20000))
    private val IDLE_ATM = listOf(IdleState("POLL", "CPUIDLE CORE POLL IDLE", 0, 0), IdleState("C1E-ATM", "MWAIT 0x00", 10, 20), IdleState("C2-ATM", "MWAIT 0x10", 20, 80),
        IdleState("C4-ATM", "MWAIT 0x30", 100, 400), IdleState("C6-ATM", "MWAIT 0x52", 140, 560))

    /**
     * Added 2026-11-20. What an x86 Linux kernel shows under /sys/devices/system/cpu, /sys/class/{dmi,drm,thermal,powercap} and
     * /sys/firmware/efi for [spec]: cpu lists, topology, caches, microcode, cpuidle, cpufreq driver and intel_pstate, SMT,
     * vulnerabilities, DMI, the PCI graphics device and a package thermal zone. Layout checked against the zenfone-2 (Z3580)
     * dump in AOSP's external/cpuinfo test/mock; newer-kernel nodes (core_cpus, smt, vulnerabilities, die_id) only appear for chips
     * with DMI (BIOS / UEFI boards run a recent android-x86 kernel; the phone-class Atoms a 3.x BSP kernel). Nothing is a serial number
     * or an id of one machine.
     */
    fun x86Files(spec: SocSpec): Map<String, String> {
        val x = spec.x86 ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        val n = spec.cores
        val cores = x.physicalCores.coerceIn(1, n)
        val perCore = (n / cores).coerceAtLeast(1)
        val modern = x.dmi != null
        val cpu = "/sys/devices/system/cpu"
        val all = (0 until n).toList()
        val allList = cpuList(all)
        out["$cpu/kernel_max"] = "${n - 1}\n"
        for (f in listOf("possible", "present", "online")) out["$cpu/$f"] = "$allList\n"
        out["$cpu/offline"] = "\n"
        val desktop = x.uarch == "desktop" || (x.uarch.isEmpty() && x.cacheKb > 4096)
        val saltwell = x.uarch == "saltwell"
        // thread siblings of cpu i: thread 0 of core c is cpu c, its siblings are c + cores * t
        fun threads(i: Int): List<Int> = (0 until perCore).map { (i % cores) + it * cores }.filter { it < n }
        val caches = when {
            desktop -> listOf(X86Cache(1, "Data", 32, 8) { threads(it) }, X86Cache(1, "Instruction", 32, 8) { threads(it) },
                X86Cache(2, "Unified", 256, 4) { threads(it) }, X86Cache(3, "Unified", x.cacheKb, 16) { all })
            saltwell -> listOf(X86Cache(1, "Data", 24, 6) { threads(it) }, X86Cache(1, "Instruction", 32, 8) { threads(it) },
                X86Cache(2, "Unified", x.cacheKb, 8) { threads(it) })
            else -> listOf(X86Cache(1, "Data", 24, 6) { listOf(it) }, X86Cache(1, "Instruction", 32, 8) { listOf(it) },
                X86Cache(2, "Unified", x.cacheKb, 16) { i -> listOf(i / 2 * 2, i / 2 * 2 + 1).filter { it < n } })
        }
        val idle = when { desktop -> IDLE_SKL; saltwell -> IDLE_ATM; else -> IDLE_BYT }
        val driver = x.cpufreqDriver
        val minMhz = spec.clusters.firstOrNull()?.minMhz ?: 800
        val maxMhz = spec.clusters.firstOrNull()?.maxMhz ?: 1000
        for (i in 0 until n) {
            val c = "$cpu/cpu$i"
            val sib = threads(i)
            val t = "$c/topology"
            out["$t/physical_package_id"] = "0\n"
            out["$t/core_id"] = "${i % cores}\n"
            out["$t/thread_siblings_list"] = cpuList(sib) + "\n"
            out["$t/thread_siblings"] = cpuMaskOf(sib) + "\n"
            out["$t/core_siblings_list"] = "$allList\n"
            out["$t/core_siblings"] = cpuMaskOf(all) + "\n"
            if (modern) {
                out["$t/core_cpus_list"] = cpuList(sib) + "\n"; out["$t/core_cpus"] = cpuMaskOf(sib) + "\n"
                out["$t/package_cpus_list"] = "$allList\n"; out["$t/package_cpus"] = cpuMaskOf(all) + "\n"
                out["$t/die_id"] = "0\n"; out["$t/die_cpus_list"] = "$allList\n"; out["$t/die_cpus"] = cpuMaskOf(all) + "\n"
            }
            caches.forEachIndexed { k, ch ->
                val d = "$c/cache/index$k"
                val sh = ch.shared(i)
                val sets = ch.kb * 1024 / 64 / ch.ways
                out["$d/level"] = "${ch.level}\n"; out["$d/type"] = ch.type + "\n"; out["$d/size"] = "${ch.kb}K\n"
                out["$d/coherency_line_size"] = "64\n"; out["$d/number_of_sets"] = "$sets\n"; out["$d/ways_of_associativity"] = "${ch.ways}\n"
                out["$d/shared_cpu_list"] = cpuList(sh) + "\n"; out["$d/shared_cpu_map"] = cpuMaskOf(sh) + "\n"
                if (modern) out["$d/physical_line_partition"] = "1\n"
            }
            if (x.microcode.isNotBlank()) { out["$c/microcode/version"] = x.microcode + "\n"; out["$c/microcode/processor_flags"] = "0x01\n" }
            idle.forEachIndexed { k, st ->
                val d = "$c/cpuidle/state$k"
                out["$d/name"] = st.name + "\n"; out["$d/desc"] = st.desc + "\n"; out["$d/latency"] = "${st.latency}\n"
                out["$d/residency"] = "${st.residency}\n"; out["$d/disable"] = "0\n"
            }
            if (driver.isNotEmpty()) {
                val f = "$c/cpufreq"
                val rel = if (driver == "sfi-cpufreq") (i / 2 * 2).let { listOf(it, it + 1).filter { v -> v < n } } else if (driver == "intel_pstate") listOf(i) else all
                out["$f/scaling_driver"] = "$driver\n"
                out["$f/affected_cpus"] = rel.joinToString(" ") + "\n"; out["$f/related_cpus"] = rel.joinToString(" ") + "\n"
                if (driver == "intel_pstate") {
                    out["$f/scaling_available_governors"] = "performance powersave\n"; out["$f/scaling_governor"] = "powersave\n"
                    out["$f/cpuinfo_transition_latency"] = "0\n"
                } else {
                    out["$f/scaling_available_governors"] = "ondemand userspace interactive performance \n"; out["$f/scaling_governor"] = "interactive\n"
                    out["$f/cpuinfo_transition_latency"] = "100000\n"
                }
            }
        }
        out["$cpu/cpuidle/current_driver"] = "intel_idle\n"
        out["$cpu/cpuidle/current_governor_ro"] = "menu\n"
        if (modern) out["$cpu/cpuidle/current_governor"] = "menu\n"
        if (driver == "intel_pstate") {
            val p = "$cpu/intel_pstate"
            val steps = ((maxMhz - minMhz) / 100 + 1).coerceAtLeast(2)
            out["$p/status"] = "active\n"; out["$p/no_turbo"] = "0\n"; out["$p/max_perf_pct"] = "100\n"
            out["$p/min_perf_pct"] = "${(minMhz * 100 / maxMhz).coerceIn(1, 100)}\n"; out["$p/num_pstates"] = "$steps\n"
            out["$p/turbo_pct"] = "${if (desktop) 31 else 36}\n"
            if (desktop) out["$p/hwp_dynamic_boost"] = "0\n"
        }
        if (modern) {
            out["$cpu/smt/active"] = (if (perCore > 1) "1" else "0") + "\n"
            out["$cpu/smt/control"] = (if (perCore > 1) "on" else "notsupported") + "\n"
            val v = "$cpu/vulnerabilities"
            val spectre1 = "Mitigation: usercopy/swapgs barriers and __user pointer sanitization"
            if (desktop) {
                out["$v/meltdown"] = "Not affected\n"; out["$v/spectre_v1"] = "$spectre1\n"
                out["$v/spectre_v2"] = "Mitigation: Enhanced / Automatic IBRS; IBPB: conditional; RSB filling; PBRSB-eIBRS: SW sequence\n"
                out["$v/spec_store_bypass"] = "Mitigation: Speculative Store Bypass disabled via prctl\n"
                out["$v/l1tf"] = "Not affected\n"; out["$v/mds"] = "Not affected\n"; out["$v/tsx_async_abort"] = "Not affected\n"
                out["$v/itlb_multihit"] = "KVM: Mitigation: VMX disabled\n"; out["$v/srbds"] = "Mitigation: Microcode\n"
                out["$v/mmio_stale_data"] = "Mitigation: Clear CPU buffers; SMT vulnerable\n"; out["$v/retbleed"] = "Not affected\n"
            } else {
                // Silvermont / Airmont (x5-Z8300 dump: cpu_meltdown, msbds_only, pti): Meltdown affected, no swapgs gadget, SSB not affected
                out["$v/meltdown"] = "Mitigation: PTI\n"; out["$v/spectre_v1"] = "Mitigation: __user pointer sanitization\n"
                out["$v/spectre_v2"] = "Mitigation: Retpolines; STIBP: disabled; RSB filling\n"
                out["$v/spec_store_bypass"] = "Not affected\n"; out["$v/l1tf"] = "Not affected\n"
                out["$v/mds"] = "Vulnerable: Clear CPU buffers attempted, no microcode; SMT disabled\n"
                out["$v/tsx_async_abort"] = "Not affected\n"; out["$v/itlb_multihit"] = "Not affected\n"; out["$v/srbds"] = "Not affected\n"
                out["$v/mmio_stale_data"] = "Not affected\n"; out["$v/retbleed"] = "Not affected\n"
            }
        }
        // thermal: the package sensor; boards with ACPI also have the firmware's zone
        out["/sys/class/thermal/thermal_zone0/type"] = (if (modern) "acpitz" else "x86_pkg_temp") + "\n"
        out["/sys/class/thermal/thermal_zone0/temp"] = "${if (desktop) 38000 else 42000}\n"
        out["/sys/class/thermal/thermal_zone0/policy"] = "step_wise\n"; out["/sys/class/thermal/thermal_zone0/mode"] = "enabled\n"
        if (modern) {
            out["/sys/class/thermal/thermal_zone1/type"] = "x86_pkg_temp\n"; out["/sys/class/thermal/thermal_zone1/temp"] = "${if (desktop) 38000 else 42000}\n"
            out["/sys/class/thermal/thermal_zone1/policy"] = "step_wise\n"; out["/sys/class/thermal/thermal_zone1/mode"] = "enabled\n"
        }
        if (desktop) {
            out["/sys/class/powercap/intel-rapl/intel-rapl:0/name"] = "package-0\n"; out["/sys/class/powercap/intel-rapl/intel-rapl:0/enabled"] = "1\n"
        }
        // PCI graphics device
        if (x.gpuPci.isNotEmpty()) {
            val (ven, dev) = x.gpuPci.split(':')
            val d = "/sys/class/drm/card0/device"
            out["$d/vendor"] = "$ven\n"; out["$d/device"] = "$dev\n"; out["$d/class"] = "0x030000\n"
            out["$d/subsystem_vendor"] = "0x0000\n"; out["$d/subsystem_device"] = "0x0000\n"
            out["$d/revision"] = (if (ven.equals("0x8086", true)) "0x0e" else "0xa1") + "\n"
        }
        // DMI / UEFI
        x.dmi?.let { m ->
            val zero = "0"
            val uuid = "00000000-0000-0000-0000-000000000000"
            val files = linkedMapOf(
                "sys_vendor" to m.sysVendor, "product_name" to m.productName, "product_version" to m.productVersion, "product_family" to m.productFamily,
                "product_sku" to m.productSku, "product_serial" to zero, "product_uuid" to uuid,
                "board_vendor" to m.boardVendor, "board_name" to m.boardName, "board_version" to m.boardVersion, "board_serial" to zero, "board_asset_tag" to "",
                "bios_vendor" to m.biosVendor, "bios_version" to m.biosVersion, "bios_date" to m.biosDate,
                "chassis_vendor" to m.chassisVendor.ifBlank { m.sysVendor }, "chassis_version" to m.chassisVersion, "chassis_type" to m.chassisType.toString(),
                "chassis_serial" to zero, "chassis_asset_tag" to "",
            )
            if (m.biosRelease.isNotBlank()) files["bios_release"] = m.biosRelease
            val sku = m.productSku
            files["modalias"] = "dmi:bvn${m.biosVendor}:bvr${m.biosVersion}:bd${m.biosDate}:" + (if (m.biosRelease.isNotBlank()) "br${m.biosRelease}:" else "") +
                "svn${m.sysVendor}:pn${m.productName}:pvr${m.productVersion}:rvn${m.boardVendor}:rn${m.boardName}:rvr${m.boardVersion}:" +
                "cvn${m.chassisVendor.ifBlank { m.sysVendor }}:ct${m.chassisType}:cvr${m.chassisVersion}:" + (if (sku.isNotBlank()) "sku$sku:" else "")
            for ((k, v) in files) { out["/sys/class/dmi/id/$k"] = v + "\n"; out["/sys/devices/virtual/dmi/id/$k"] = v + "\n" }
            out["/sys/firmware/efi/fw_platform_size"] = (if (x.physBits >= 39) "64" else "32") + "\n"   // Bay / Cherry Trail tablets boot a 32-bit UEFI
        }
        return out
    }

    // ---- frequencies ----------------------------------------------------------------------------------------------

    /** Six steps from [minKhz] to [maxKhz], as scaling_available_frequencies lists them. */
    fun freqSteps(minKhz: Int, maxKhz: Int): List<Int> {
        if (maxKhz <= minKhz) return listOf(maxKhz)
        val n = 6
        return (0 until n).map { i -> if (i == n - 1) maxKhz else (minKhz + (maxKhz - minKhz) * i / (n - 1)) / 1000 * 1000 }.distinct()
    }

    /** The old fixed table the engine always showed; where the phone's own cannot be read. */
    const val FALLBACK_MIN_KHZ = 384_000
    const val FALLBACK_MAX_KHZ = 1_512_000

    // ---- resolve --------------------------------------------------------------------------------------------------

    /**
     * @param setting the VM's soc setting (blank = default)
     * @param tango the image is a Project Tango one
     * @param guest32 the firmware's programs are 32-bit ARM
     */
    fun resolve(setting: String, custom: SocSpec?, tango: Boolean, guest32: Boolean, api: Int, host: HostSocSource, isa: SocIsa = SocIsa.ARM): SocResolved {
        val id = effectiveId(setting, tango, isa)
        val spec: SocSpec? = when (id) {
            YELLOWSTONE -> YELLOWSTONE_SPEC
            // (a custom chip written for the other instruction set is not used: the phone's own chip is reported instead)
            CUSTOM -> custom?.sanitized()?.takeIf { it.isa == isa } ?: presetsFor(isa).firstOrNull { it.id == "sm8150" } ?: presetsFor(isa).first()
            HOST -> null
            else -> preset(id)
        }
        if (spec != null) {
            val freqs = ArrayList<Pair<Int, Int>>()
            for (c in spec.clusters) repeat(c.count) { freqs += (c.minMhz * 1000) to maxOf(c.maxMhz, c.minMhz) * 1000 }
            val n = spec.cores
            return SocResolved(spec.id, spec.label, cpuinfo(spec, guest32), soc0Files(spec), props(spec, api), n, freqs.take(n), spec.gpuVendor, spec.gpuRenderer, files(spec))
        }
        // the phone itself
        val chip = host.prop("ro.soc.model").orEmpty().ifBlank { host.prop("ro.hardware.chipname").orEmpty() }
            .ifBlank { host.prop("ro.board.platform").orEmpty() }.ifBlank { host.prop("ro.hardware").orEmpty() }
        val raw = host.cpuinfo()
        val text = hostCpuinfo(raw.orEmpty(), guest32, chip)
        val parsed = Regex("(?m)^processor\\s*:").findAll(text).count()
        val n = (if (parsed > 0) parsed else host.cpuCount()).coerceIn(1, MAX_CORES)
        val soc0 = linkedMapOf<String, String>()
        // (the phone's serial_number is its own identifier: never copied)
        for (f in listOf("family", "machine", "soc_id", "revision")) host.soc0(f)?.trim()?.takeIf { it.isNotEmpty() }?.let { soc0[f] = SocSpec.line(it, 32) }
        if ("machine" !in soc0) host.prop("ro.soc.model")?.takeIf { it.isNotBlank() }?.let { soc0["machine"] = SocSpec.line(it, 32) }
        val props = linkedMapOf<String, String>()
        host.prop("ro.board.platform")?.takeIf { it.isNotBlank() }?.let { props["ro.board.platform"] = it }
        host.prop("ro.hardware.chipname")?.takeIf { it.isNotBlank() }?.let { props["ro.hardware.chipname"] = it }
        if (api >= 31) {
            host.prop("ro.soc.manufacturer")?.takeIf { it.isNotBlank() }?.let { props["ro.soc.manufacturer"] = it }
            host.prop("ro.soc.model")?.takeIf { it.isNotBlank() }?.let { props["ro.soc.model"] = it }
        }
        val freqs = (0 until n).map { host.cpuFreqKhz(it) ?: (FALLBACK_MIN_KHZ to FALLBACK_MAX_KHZ) }
        return SocResolved(HOST, "Host", text, soc0, props, n, freqs)
    }

    // ---- the custom chip's core layout as text ---------------------------------------------------------------------

    /** One line per cluster: "count implementer variant part revision maxMHz minMHz", numbers in hex with 0x or decimal. */
    fun encodeClusters(c: List<SocCluster>): String =
        c.joinToString("\n") { "${it.count} ${hex(it.implementer, 2)} ${hex(it.variant)} ${hex(it.part, 3)} ${it.revision} ${it.maxMhz} ${it.minMhz}" }

    /** Null when a line is not valid; the editor then keeps the last good layout. */
    fun parseClusters(text: String): List<SocCluster>? {
        fun num(s: String): Int? = if (s.startsWith("0x", true)) s.substring(2).toIntOrNull(16) else s.toIntOrNull()
        val out = ArrayList<SocCluster>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val t = line.split(Regex("[\\s,:;]+")).filter { it.isNotEmpty() }.map { num(it) ?: return null }
            if (t.size < 6) return null
            val (count, impl, variant, part, rev, max) = t
            val min = t.getOrNull(6) ?: 300
            if (count !in 1..MAX_CORES || impl !in 0..0xff || variant !in 0..0xf || part !in 0..0xfff || rev !in 0..0xf || max !in 100..6000 || min !in 100..6000) return null
            out += SocCluster(count, impl, variant, part, rev, max, min)
        }
        if (out.isEmpty() || out.size > 4 || out.sumOf { it.count } > MAX_CORES) return null
        return out
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}
