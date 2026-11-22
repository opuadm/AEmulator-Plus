package app.aemu.importer

import app.aemu.importer.tools.FirmwareToolset
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * What a file that came out of a container is, as far as the import is concerned. Every container (zip, 7z, rar, KDZ / DZ,
 * PAC, payload.bin, a GPT disk, a super image ...) only unpacks; [Part] is what [Importer.importStaged] then decides on.
 */
internal enum class PartKind {
    /** The root of the guest: /system (or the whole root file system on system-as-root devices). Required. */
    SYSTEM,
    /** /vendor, /odm, /product, /system_ext, /oem-like skin partitions: read into the tree, never boot-critical. */
    VENDOR, OEM, EXTRA, GAPPS,
    /** boot-side images: the ramdisk, the kernel version, the recovery. */
    BOOT, VENDOR_BOOT, RECOVERY,
    /** A dynamic-partitions container: replaced by its logical partitions before anything is read. */
    SUPER,
    /** Only set by a caller's hint (a partition name the guest root cannot hold). Nothing is dropped by name: what is not whitelisted is [OTHER]. */
    SKIP,
    /** Not a partition: a nested archive, a firmware container, something unknown. Goes back to [Importer.handle]. */
    OTHER,
}

/** [mount] is where the partition is read to ("system", "vendor", "system/product", "odm", "my_stock" ...) for the kinds that have one. */
internal class Part(val kind: PartKind, val mount: String? = null) {
    companion object {
        val SYSTEM = Part(PartKind.SYSTEM, "system")
        val VENDOR = Part(PartKind.VENDOR, "vendor")
        val OEM = Part(PartKind.OEM, "oem")
        val GAPPS = Part(PartKind.GAPPS, "gapps")
        val BOOT = Part(PartKind.BOOT)
        val VENDOR_BOOT = Part(PartKind.VENDOR_BOOT)
        val RECOVERY = Part(PartKind.RECOVERY)
        val SUPER = Part(PartKind.SUPER)
        val SKIP = Part(PartKind.SKIP)
        val OTHER = Part(PartKind.OTHER)
    }
}

/**
 * One unpacked piece of a container: a partition image, a part of a super image, a set of sparse chunks, an archive. What it
 * is made of (a temp file, an entry of an open zip, extents of another image) stays behind [use]; nothing is opened or copied
 * until the pipeline gets to it, so a super image's logical partitions are read in place.
 */
internal abstract class Staged(
    /** The name the matchers know it by ("system.img", "vendor_a.bin", "Firmware/boot.img"). */
    val name: String,
    /** What the container that unpacked it said it is (PAC / KDZ / OFP ...), used only when the name says nothing. */
    val role: FirmwareToolset.Role? = null,
) {
    abstract val size: Long

    /** Set by the caller when it already knows what this is (the arm of [Importer.handle] that matched it by content). */
    var hint: Part? = null

    /** Only wanted when nothing of this kind came from a direct image: a system built from a block-OTA pair, a split sparse set. */
    var fallback = false

    /** The raw image is the stock one an OTA can be applied to ([Importer.keepStockImage]). */
    var keepStock = false

    /** 0 = a partition of its own, 1 = cut out of a super image: a standalone image of the same partition wins. */
    var origin = 0

    abstract fun <T> use(block: (RandomSource) -> T): T

    /** Drops whatever backs this piece (a temp file). Called as soon as the piece is applied, so the disk peak stays low. */
    open fun release() {}
}

/** A file on disk. [owned]: it is a private temp copy, deleted by [release]. */
internal class FileStaged(val file: File, name: String, role: FirmwareToolset.Role? = null, private val owned: Boolean = true) : Staged(name, role) {
    override val size: Long get() = file.length()
    override fun <T> use(block: (RandomSource) -> T): T =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { block(ChannelSource(it)) }
    override fun release() { if (owned) file.delete() }
}

/** A source somebody else keeps open for the whole import (the caller closes it). */
internal class SourceStaged(name: String, private val src: RandomSource, role: FirmwareToolset.Role? = null) : Staged(name, role) {
    override val size: Long get() = src.size
    override fun <T> use(block: (RandomSource) -> T): T = block(src)
}

/** Extents of another piece: a logical partition of a super image. A sparse parent (fastboot super.img) is read through its sparse layer. */
internal class SliceStaged(name: String, private val parent: Staged, private val extents: List<Pair<Long, Long>>) : Staged(name) {
    override val size: Long = extents.sumOf { it.second }
    override fun <T> use(block: (RandomSource) -> T): T = parent.use { p ->
        block(ExtentSource(if (SparseSource.probe(p)) SparseSource(listOf(p)) else p, extents))
    }
}

/** A partition delivered as several sparse chunks (system.img_sparsechunk.N ...), already in order. */
internal class SparseSetStaged(name: String, private val parts: List<Staged>) : Staged(name) {
    override val size: Long get() = parts.sumOf { it.size }
    override fun <T> use(block: (RandomSource) -> T): T = open(0, ArrayList(), block)
    private fun <T> open(i: Int, acc: MutableList<RandomSource>, block: (RandomSource) -> T): T =
        if (i == parts.size) block(SparseSource(acc.toList()))
        else parts[i].use { s -> acc.add(s); try { open(i + 1, acc, block) } finally { acc.removeAt(acc.size - 1) } }
    override fun release() { parts.forEach { it.release() } }
}

/** The one place that says what a file is called what it is called. Order mirrors what [Importer.handle] always did. */
internal object PartKinds {
    private val VENDOR_BOOT = Regex("(?i)vendor_boot(_[ab])?(\\.img|\\.bin)?")

    fun isVendorBoot(base: String) = VENDOR_BOOT.matches(ImageCodecs.stripSuffix(base))

    /** A logical partition of a super image, by its name without the slot suffix. */
    fun logical(name: String): Part = when (name) {
        "system" -> Part.SYSTEM
        "vendor" -> Part.VENDOR
        else -> {
            val mount = PartitionNames.extraMount("$name.img") ?: PartitionNames.logicalMount(name)
            if (mount == null) Part.SKIP else Part(PartKind.EXTRA, mount)
        }
    }

    /**
     * [base] is the file name, [h] its first bytes, [bare] whether a name without an extension may count
     * ([PartitionNames.bareOk]), [role] what the container that unpacked it said.
     */
    fun classify(base: String, h: ByteArray, bare: Boolean, role: FirmwareToolset.Role?): Part {
        if (role == FirmwareToolset.Role.OEM || PartitionNames.isOem(base, bare)) return Part.OEM
        if (PartitionNames.isSuper(base, bare)) return Part.SUPER
        if (PartitionNames.isVendor(base, bare)) return Part.VENDOR
        PartitionNames.extraMount(base, bare)?.let { return Part(PartKind.EXTRA, it) }
        if (isVendorBoot(base)) return Part.VENDOR_BOOT
        if (PartitionNames.isRecovery(base, bare)) return Part.RECOVERY
        if (role == FirmwareToolset.Role.RECOVERY && h.size >= 8 &&
            (String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!" || h[0] == 0x04.toByte() && h[1] == 0x22.toByte() && h[2] == 0x4d.toByte() && h[3] == 0x18.toByte())) return Part.RECOVERY
        if (PartitionNames.isBoot(base, bare) || role == FirmwareToolset.Role.BOOT) return Part.BOOT
        if (PartitionNames.isSystem(base, bare) || role == FirmwareToolset.Role.SYSTEM) return Part.SYSTEM
        if (h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!") return Part.BOOT
        return Part.OTHER
    }
}
