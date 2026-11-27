/* AEmulator Plus addition: A/B (payload.bin) incremental OTA support. GPL-3.0; see LICENSE. */
package app.aemu.core

import app.aemu.archive.ArcStreams
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** A payload that cannot be applied; [message] is meant for the person using the app. */
class PayloadException(message: String) : IOException(message)

/**
 * Reads and applies an update_engine payload (`payload.bin`, the content of an A/B OTA zip: Xiaomi MIUI / HyperOS,
 * Pixel, ...). A full payload only writes new data; an incremental one is made against the previous build and
 * rebuilds every partition from the OLD partition image plus a list of operations:
 *  - SOURCE_COPY: blocks of the old image are copied to blocks of the new one;
 *  - SOURCE_BSDIFF / BROTLI_BSDIFF: the blocks are the old data of a binary patch (bzip2 "BSDIFF40" / "BSDF2" with
 *    brotli streams, which is what current MIUI / HyperOS OTAs use);
 *  - REPLACE / REPLACE_BZ / REPLACE_XZ: new data stored in the payload; ZERO / DISCARD: nothing to write.
 * The old image has to be bit for bit the one the OTA was made for. Every operation that reads the old image carries
 * the SHA-256 of what it reads, and every blob of data the SHA-256 of the blob; both are checked, so an image that
 * differs anywhere the OTA looks at is reported, never silently patched into garbage.
 *
 * Not supported (reported before anything is done): PUFFDIFF, ZUCCHINI and LZ4DIFF operations (patches of compressed
 * data such as APKs / zlib streams), and the in-place MOVE / BSDIFF of the oldest A/B payloads.
 */
object PayloadOta {
    const val REPLACE = 0
    const val REPLACE_BZ = 1
    const val MOVE = 2
    const val BSDIFF = 3
    const val SOURCE_COPY = 4
    const val SOURCE_BSDIFF = 5
    const val ZERO = 6
    const val DISCARD = 7
    const val REPLACE_XZ = 8
    const val PUFFDIFF = 9
    const val BROTLI_BSDIFF = 10
    const val ZUCCHINI = 11
    const val LZ4DIFF_BSDIFF = 12
    const val LZ4DIFF_PUFFDIFF = 13

    private val SUPPORTED = setOf(REPLACE, REPLACE_BZ, SOURCE_COPY, SOURCE_BSDIFF, ZERO, DISCARD, REPLACE_XZ, BROTLI_BSDIFF)
    private val NEEDS_SOURCE = setOf(SOURCE_COPY, SOURCE_BSDIFF, BROTLI_BSDIFF, PUFFDIFF, ZUCCHINI, LZ4DIFF_BSDIFF, LZ4DIFF_PUFFDIFF, MOVE, BSDIFF)

    private fun typeName(t: Int) = when (t) {
        REPLACE -> "REPLACE"; REPLACE_BZ -> "REPLACE_BZ"; MOVE -> "MOVE"; BSDIFF -> "BSDIFF"; SOURCE_COPY -> "SOURCE_COPY"
        SOURCE_BSDIFF -> "SOURCE_BSDIFF"; ZERO -> "ZERO"; DISCARD -> "DISCARD"; REPLACE_XZ -> "REPLACE_XZ"; PUFFDIFF -> "PUFFDIFF"
        BROTLI_BSDIFF -> "BROTLI_BSDIFF"; ZUCCHINI -> "ZUCCHINI"; LZ4DIFF_BSDIFF -> "LZ4DIFF_BSDIFF"
        LZ4DIFF_PUFFDIFF -> "LZ4DIFF_PUFFDIFF"; else -> "type $t"
    }

    /** The decompressors; replaced by the tests, which have no native libraries. */
    internal interface Codecs {
        fun brotli(i: InputStream): InputStream
        fun bzip2(i: InputStream): InputStream
        fun xz(i: InputStream): InputStream
    }

    internal object ArcCodecs : Codecs {
        override fun brotli(i: InputStream): InputStream = ArcStreams.brotli(i)
        override fun bzip2(i: InputStream): InputStream = ArcStreams.bzip2(i)
        override fun xz(i: InputStream): InputStream = ArcStreams.xz(i)
    }

    @Volatile internal var codecs: Codecs = ArcCodecs

    /** [start] is a block number; a start of 2^64-1 (a "sparse hole") reads as zeros. */
    class Extent(val start: Long, val blocks: Long) {
        val isHole: Boolean get() = start == -1L
    }

    class Operation(
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        val src: List<Extent>,
        val dst: List<Extent>,
        /** bytes of the new data (the last block of [dst] may be only partly used); 0 if the payload does not say */
        val dstLength: Long,
        val dataSha256: ByteArray?,
        val srcSha256: ByteArray?,
    )

    class PartitionUpdate(
        val name: String,
        val oldSize: Long,
        val oldSha256: ByteArray?,
        val newSize: Long,
        val newSha256: ByteArray?,
        val ops: List<Operation>,
    ) {
        /** True if some operation reads the old image. */
        val needsSource: Boolean get() = ops.any { it.type in NEEDS_SOURCE }
    }

    class Manifest(
        val blockSize: Int,
        val minorVersion: Int,
        val maxTimestamp: Long,
        val securityPatch: String?,
        val partitions: List<PartitionUpdate>,
        /** where the data blobs start, relative to the start of the payload */
        val dataOffset: Long,
    ) {
        /** An incremental payload: one that reads the old partitions. */
        val incremental: Boolean get() = partitions.any { it.needsSource }
        fun partition(name: String): PartitionUpdate? = partitions.firstOrNull { it.name == name }
    }

    /** `payload.bin` as a window of a file: a stored entry of the OTA zip is read in place, [offset] being where it starts. */
    class Payload(val file: File, val offset: Long = 0, val length: Long = file.length() - offset)

    // ------------------------------------------------------------------ manifest

    fun readManifest(p: Payload): Manifest {
        FileChannel.open(p.file.toPath(), StandardOpenOption.READ).use { ch ->
            val head = ByteBuffer.allocate(24)
            readFully(ch, head, p.offset)
            if (head.get(0) != 'C'.code.toByte() || head.get(1) != 'r'.code.toByte() || head.get(2) != 'A'.code.toByte() || head.get(3) != 'U'.code.toByte())
                throw PayloadException("not an update payload (no CrAU header)")
            val version = head.getLong(4)
            if (version != 2L) throw PayloadException("payload version $version is not supported (only version 2)")
            val manifestSize = head.getLong(12)
            val sigSize = head.getInt(20).toLong() and 0xffffffffL
            if (manifestSize <= 0 || manifestSize > (1L shl 28) || 24 + manifestSize + sigSize > p.length)
                throw PayloadException("the payload header is damaged (truncated download?)")
            val mb = ByteBuffer.allocate(manifestSize.toInt())
            readFully(ch, mb, p.offset + 24)
            return parseManifest(mb.array(), 24 + manifestSize + sigSize)
        }
    }

    internal fun parseManifest(b: ByteArray, dataOffset: Long): Manifest {
        val r = Proto(b)
        var blockSize = 4096
        var minor = 0
        var maxTs = 0L
        var patch: String? = null
        val parts = ArrayList<PartitionUpdate>()
        while (r.more()) {
            val f = r.tag()
            val w = r.wire
            when {
                f == 3 && w == 0 -> blockSize = r.varint().toInt()
                f == 12 && w == 0 -> minor = r.varint().toInt()
                f == 13 && w == 2 -> parts.add(parsePartition(r.bytes()))
                f == 14 && w == 0 -> maxTs = r.varint()
                f == 18 && w == 2 -> patch = String(r.bytes(), Charsets.UTF_8)
                else -> r.skip(w)
            }
        }
        if (blockSize <= 0 || blockSize and (blockSize - 1) != 0) throw PayloadException("the payload has an odd block size ($blockSize)")
        if (parts.isEmpty()) throw PayloadException("the payload lists no partitions")
        return Manifest(blockSize, minor, maxTs, patch, parts, dataOffset)
    }

    private fun parsePartition(b: ByteArray): PartitionUpdate {
        val r = Proto(b)
        var name = ""
        var oldSize = 0L; var oldHash: ByteArray? = null
        var newSize = 0L; var newHash: ByteArray? = null
        val ops = ArrayList<Operation>()
        while (r.more()) {
            val f = r.tag()
            val w = r.wire
            when {
                f == 1 && w == 2 -> name = String(r.bytes(), Charsets.UTF_8)
                f == 6 && w == 2 -> parseInfo(r.bytes()).let { oldSize = it.first; oldHash = it.second }
                f == 7 && w == 2 -> parseInfo(r.bytes()).let { newSize = it.first; newHash = it.second }
                f == 8 && w == 2 -> ops.add(parseOperation(r.bytes()))
                else -> r.skip(w)
            }
        }
        return PartitionUpdate(name, oldSize, oldHash, newSize, newHash, ops)
    }

    private fun parseInfo(b: ByteArray): Pair<Long, ByteArray?> {
        val r = Proto(b)
        var size = 0L; var hash: ByteArray? = null
        while (r.more()) {
            val f = r.tag()
            val w = r.wire
            when {
                f == 1 && w == 0 -> size = r.varint()
                f == 2 && w == 2 -> hash = r.bytes()
                else -> r.skip(w)
            }
        }
        return size to hash
    }

    private fun parseOperation(b: ByteArray): Operation {
        val r = Proto(b)
        var type = 0; var off = 0L; var len = 0L; var dstLen = 0L
        val src = ArrayList<Extent>(); val dst = ArrayList<Extent>()
        var dataHash: ByteArray? = null; var srcHash: ByteArray? = null
        while (r.more()) {
            val f = r.tag()
            val w = r.wire
            when {
                f == 1 && w == 0 -> type = r.varint().toInt()
                f == 2 && w == 0 -> off = r.varint()
                f == 3 && w == 0 -> len = r.varint()
                f == 4 && w == 2 -> src.add(parseExtent(r.bytes()))
                f == 6 && w == 2 -> dst.add(parseExtent(r.bytes()))
                f == 7 && w == 0 -> dstLen = r.varint()
                f == 8 && w == 2 -> dataHash = r.bytes()
                f == 9 && w == 2 -> srcHash = r.bytes()
                else -> r.skip(w)
            }
        }
        return Operation(type, off, len, src, dst, dstLen, dataHash, srcHash)
    }

    private fun parseExtent(b: ByteArray): Extent {
        val r = Proto(b)
        var start = 0L; var n = 0L
        while (r.more()) {
            val f = r.tag()
            val w = r.wire
            when {
                f == 1 && w == 0 -> start = r.varint()
                f == 2 && w == 0 -> n = r.varint()
                else -> r.skip(w)
            }
        }
        return Extent(start, n)
    }

    private class Proto(val b: ByteArray) {
        var p = 0
        var wire = 0
        fun more() = p < b.size
        fun varint(): Long {
            var v = 0L; var shift = 0
            while (p < b.size && shift < 70) {
                val c = b[p++].toInt() and 0xff
                if (shift < 64) v = v or ((c and 0x7f).toLong() shl shift)
                if (c and 0x80 == 0) return v
                shift += 7
            }
            throw PayloadException("the payload manifest is damaged")
        }
        fun tag(): Int { val k = varint(); wire = (k and 7).toInt(); return (k ushr 3).toInt() }
        fun bytes(): ByteArray {
            val n = varint()
            if (n < 0 || n > b.size - p) throw PayloadException("the payload manifest is damaged")
            return b.copyOfRange(p, p + n.toInt()).also { p += n.toInt() }
        }
        fun skip(w: Int) {
            when (w) {
                0 -> varint()
                1 -> p += 8
                2 -> bytes()
                5 -> p += 4
                else -> throw PayloadException("the payload manifest is damaged")
            }
            if (p > b.size) throw PayloadException("the payload manifest is damaged")
        }
    }

    // ------------------------------------------------------------------ checks before any work

    /** The kinds of operation the OTA uses that this app cannot apply, empty if it can apply all of them. */
    fun unsupported(m: Manifest, only: Collection<String>? = null): Set<String> =
        m.partitions.filter { only == null || it.name in only }
            .flatMap { it.ops }.map { it.type }.filter { it !in SUPPORTED }.map { typeName(it) }.toSortedSet()

    /**
     * Checks the payload against its payload_properties.txt (`FILE_HASH`, `FILE_SIZE`): a truncated or damaged
     * download is found before the VM is touched. Returns false if the properties name no hash to check.
     */
    fun verifyFile(p: Payload, properties: String?): Boolean {
        val kv = properties.orEmpty().lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
        val size = kv["FILE_SIZE"]?.toLongOrNull()
        if (size != null && size != p.length) throw PayloadException("the payload is $size bytes in its description but ${p.length} in the file (damaged download?)")
        val want = kv["FILE_HASH"]?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        val md = MessageDigest.getInstance("SHA-256")
        FileChannel.open(p.file.toPath(), StandardOpenOption.READ).use { ch ->
            val buf = ByteBuffer.allocate(1 shl 20)
            var pos = p.offset
            val end = p.offset + p.length
            while (pos < end) {
                buf.clear(); buf.limit(minOf(buf.capacity().toLong(), end - pos).toInt())
                val n = ch.read(buf, pos)
                if (n <= 0) throw PayloadException("the payload file is shorter than expected (damaged download?)")
                md.update(buf.array(), 0, n); pos += n
            }
        }
        if (!md.digest().contentEquals(want)) throw PayloadException("the payload does not match its checksum (damaged download?)")
        return true
    }

    // ------------------------------------------------------------------ applying one partition

    /**
     * Builds the new image of [part] in [newImage] from [oldImage] (null for a partition no operation reads) and the
     * payload. [progress] gets (bytes written, bytes to write) now and then.
     */
    fun apply(
        payload: Payload,
        manifest: Manifest,
        part: PartitionUpdate,
        oldImage: File?,
        newImage: File,
        progress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ) {
        val bad = unsupported(manifest, listOf(part.name))
        if (bad.isNotEmpty()) throw PayloadException("${part.name}: this OTA uses ${bad.joinToString()} operations, which cannot be applied here")
        if (part.needsSource && (oldImage == null || !oldImage.isFile))
            throw PayloadException("${part.name}: the old partition image is needed and is not available")
        val bs = manifest.blockSize
        val total = part.ops.sumOf { op -> op.dst.sumOf { it.blocks } * bs }
        var done = 0L
        FileChannel.open(payload.file.toPath(), StandardOpenOption.READ).use { pch ->
            FileChannel.open(newImage.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ).use { nch ->
                nch.truncate(0)
                // the file is sparse and zero: ZERO / DISCARD and the padding after a short patch need no write
                if (part.newSize > 0) {
                    nch.position(part.newSize - 1); nch.write(ByteBuffer.wrap(ByteArray(1))); nch.truncate(part.newSize)
                }
                val oldCh = if (oldImage != null && oldImage.isFile) FileChannel.open(oldImage.toPath(), StandardOpenOption.READ) else null
                try {
                    for ((i, op) in part.ops.withIndex()) {
                        if (cancelled()) throw IOException("cancelled")
                        val where = "${part.name} operation $i (${typeName(op.type)})"
                        applyOp(payload, pch, manifest, op, oldCh, nch, bs, where)
                        done += op.dst.sumOf { it.blocks } * bs
                        if (i % 16 == 0 || i == part.ops.size - 1) progress(done, total)
                    }
                } finally { oldCh?.close() }
            }
        }
    }

    private fun applyOp(p: Payload, pch: FileChannel, m: Manifest, op: Operation, oldCh: FileChannel?, nch: FileChannel, bs: Int, where: String) {
        if (op.type !in SUPPORTED) throw PayloadException("$where is not supported")
        val dst = Sink(nch, op.dst, bs)
        when (op.type) {
            ZERO, DISCARD -> return
            SOURCE_COPY -> {
                val src = Source(oldCh ?: throw PayloadException("$where needs the old image"), op.src, bs)
                if (src.size != dst.size) throw PayloadException("$where is damaged (source and target differ in size)")
                val md = op.srcSha256?.let { MessageDigest.getInstance("SHA-256") }
                val buf = ByteArray(1 shl 20)
                var pos = 0L
                while (pos < src.size) {
                    val k = minOf(buf.size.toLong(), src.size - pos).toInt()
                    src.read(pos, buf, 0, k)
                    md?.update(buf, 0, k)
                    dst.write(buf, 0, k)
                    pos += k
                }
                if (md != null && !md.digest().contentEquals(op.srcSha256)) throw sourceMismatch(where)
            }
            REPLACE, REPLACE_BZ, REPLACE_XZ -> {
                val blob = readBlob(p, pch, m, op, where)
                val raw: InputStream = ByteArrayInputStream(blob)
                val ins = when (op.type) { REPLACE_BZ -> codecs.bzip2(raw); REPLACE_XZ -> codecs.xz(raw); else -> raw }
                ins.use { copyPadded(it, dst) }
            }
            SOURCE_BSDIFF, BROTLI_BSDIFF -> {
                val src = Source(oldCh ?: throw PayloadException("$where needs the old image"), op.src, bs)
                op.srcSha256?.let { want ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(1 shl 20)
                    var pos = 0L
                    while (pos < src.size) {
                        val k = minOf(buf.size.toLong(), src.size - pos).toInt()
                        src.read(pos, buf, 0, k); md.update(buf, 0, k); pos += k
                    }
                    if (!md.digest().contentEquals(want)) throw sourceMismatch(where)
                }
                val patch = readBlob(p, pch, m, op, where)
                val out = object : OutputStream() {
                    override fun write(b: Int) { dst.write(byteArrayOf(b.toByte()), 0, 1) }
                    override fun write(b: ByteArray, off: Int, len: Int) { dst.write(b, off, len) }
                }
                val written = bspatch(src, patch, out, where)
                if (written > dst.size) throw PayloadException("$where is damaged (the patch makes more data than fits)")
            }
        }
    }

    private fun sourceMismatch(where: String) = PayloadException(
        "$where: the partition this update is applied to differs from the build it was made for " +
            "(files were changed, or it is not the same firmware)")

    private fun readBlob(p: Payload, pch: FileChannel, m: Manifest, op: Operation, where: String): ByteArray {
        if (op.dataLength < 0 || op.dataLength > (1L shl 28)) throw PayloadException("$where is damaged (data size ${op.dataLength})")
        val at = p.offset + m.dataOffset + op.dataOffset
        if (at + op.dataLength > p.offset + p.length) throw PayloadException("$where reaches beyond the end of the payload (truncated download?)")
        val bb = ByteBuffer.allocate(op.dataLength.toInt())
        readFully(pch, bb, at)
        op.dataSha256?.let { if (!MessageDigest.getInstance("SHA-256").digest(bb.array()).contentEquals(it)) throw PayloadException("$where: its data does not match the checksum (damaged download?)") }
        return bb.array()
    }

    /** Copies [ins] to [dst]; what is missing at the end stays zero (the new image is zero to begin with). */
    private fun copyPadded(ins: InputStream, dst: Sink) {
        val buf = ByteArray(1 shl 20)
        var left = dst.size
        while (left > 0) {
            val k = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (k <= 0) return
            dst.write(buf, 0, k); left -= k
        }
    }

    // ------------------------------------------------------------------ extents

    /** The old data of an operation: its extents, one after the other, read at random. */
    internal class Source(private val ch: FileChannel, private val ext: List<Extent>, private val bs: Int) {
        private val starts = LongArray(ext.size + 1)
        val size: Long
        init {
            for (i in ext.indices) starts[i + 1] = starts[i] + ext[i].blocks * bs
            size = starts[ext.size]
        }

        /** Reads [len] bytes at [pos] (inside [size]); holes and what lies beyond the end of the image read as zeros. */
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
            var p = pos; var o = off; var left = len
            var i = java.util.Arrays.binarySearch(starts, p).let { if (it >= 0) it else -it - 2 }
            if (i < 0) i = 0
            while (left > 0) {
                // empty extents (and equal entries of [starts]) are passed over
                while (i < ext.size && p >= starts[i + 1]) i++
                if (i >= ext.size || p < starts[i]) throw PayloadException("a read lies outside the source extents")
                val e = ext[i]
                val inExt = p - starts[i]
                val n = minOf(left.toLong(), starts[i + 1] - p).toInt()
                if (e.isHole) java.util.Arrays.fill(buf, o, o + n, 0)
                else {
                    val bb = ByteBuffer.wrap(buf, o, n)
                    var at = e.start * bs + inExt
                    while (bb.hasRemaining()) {
                        val k = ch.read(bb, at)
                        if (k <= 0) { java.util.Arrays.fill(buf, bb.position(), o + n, 0); break }
                        at += k
                    }
                }
                p += n; o += n; left -= n
            }
        }
    }

    /** Where the new data of an operation goes: its extents, one after the other, written in order. */
    internal class Sink(private val ch: FileChannel, private val ext: List<Extent>, private val bs: Int) {
        val size: Long = ext.sumOf { it.blocks } * bs
        private var i = 0
        private var inExt = 0L
        fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var left = len
            while (left > 0) {
                while (i < ext.size && inExt >= ext[i].blocks * bs) { i++; inExt = 0 }
                if (i >= ext.size) throw PayloadException("more data than the target extents hold")
                val e = ext[i]
                if (e.isHole) throw PayloadException("a target extent is a hole")
                val n = minOf(left.toLong(), e.blocks * bs - inExt).toInt()
                val bb = ByteBuffer.wrap(b, o, n)
                var at = e.start * bs + inExt
                while (bb.hasRemaining()) at += ch.write(bb, at)
                inExt += n; o += n; left -= n
            }
        }
    }

    // ------------------------------------------------------------------ bspatch

    private fun offtin(b: ByteArray, p: Int): Long {
        var y = (b[p + 7].toLong() and 0x7f)
        for (i in 6 downTo 0) y = (y shl 8) or (b[p + i].toLong() and 0xff)
        return if (b[p + 7].toInt() and 0x80 != 0) -y else y
    }

    /**
     * Applies a bsdiff patch (classic "BSDIFF40", or Android's "BSDF2" whose three streams are each stored,
     * bzip2 or brotli compressed) to [old] and writes the result to [out]. Returns the number of bytes made.
     */
    internal fun bspatch(old: Source, patch: ByteArray, out: OutputStream, where: String): Long {
        if (patch.size < 32) throw PayloadException("$where: the patch is too short")
        val magic = String(patch, 0, 5, Charsets.ISO_8859_1)
        val kinds = IntArray(3)
        when {
            magic == "BSDF2" -> for (i in 0..2) kinds[i] = patch[5 + i].toInt()
            String(patch, 0, 8, Charsets.ISO_8859_1) == "BSDIFF40" -> kinds.fill(1)
            else -> throw PayloadException("$where: the patch is neither BSDIFF40 nor BSDF2")
        }
        val ctrlLen = offtin(patch, 8); val diffLen = offtin(patch, 16); val newSize = offtin(patch, 24)
        if (ctrlLen < 0 || diffLen < 0 || newSize < 0 || 32 + ctrlLen + diffLen > patch.size) throw PayloadException("$where: the patch is damaged")
        fun stream(kind: Int, from: Long, to: Long): DataInputStream {
            val raw = ByteArrayInputStream(patch, from.toInt(), (to - from).toInt())
            val s = when (kind) {
                0 -> raw
                1 -> codecs.bzip2(raw)
                2 -> codecs.brotli(raw)
                else -> throw PayloadException("$where: the patch uses an unknown compression ($kind)")
            }
            return DataInputStream(s.buffered(1 shl 16))
        }
        val ctrl = stream(kinds[0], 32, 32 + ctrlLen)
        val diff = stream(kinds[1], 32 + ctrlLen, 32 + ctrlLen + diffLen)
        val extra = stream(kinds[2], 32 + ctrlLen + diffLen, patch.size.toLong())
        val oldSize = old.size
        val cb = ByteArray(24)
        val d = ByteArray(1 shl 16); val o = ByteArray(1 shl 16)
        var newPos = 0L; var oldPos = 0L
        try {
            while (newPos < newSize) {
                ctrl.readFully(cb)
                val add = offtin(cb, 0); val copy = offtin(cb, 8); val seek = offtin(cb, 16)
                if (add < 0 || copy < 0 || newPos + add + copy > newSize) throw PayloadException("$where: the patch is damaged")
                var left = add
                while (left > 0) {
                    val k = minOf(left, d.size.toLong()).toInt()
                    diff.readFully(d, 0, k)
                    // the old bytes under this stretch; outside the old data they count as zero
                    val from = maxOf(oldPos, 0L); val to = minOf(oldPos + k, oldSize)
                    java.util.Arrays.fill(o, 0, k, 0)
                    if (to > from) old.read(from, o, (from - oldPos).toInt(), (to - from).toInt())
                    for (j in 0 until k) d[j] = (d[j] + o[j]).toByte()
                    out.write(d, 0, k)
                    oldPos += k; newPos += k; left -= k
                }
                left = copy
                while (left > 0) {
                    val k = minOf(left, d.size.toLong()).toInt()
                    extra.readFully(d, 0, k)
                    out.write(d, 0, k)
                    newPos += k; left -= k
                }
                oldPos += seek
            }
        } catch (e: EOFException) {
            throw PayloadException("$where: the patch ends too early")
        } finally {
            ctrl.close(); diff.close(); extra.close()
        }
        return newPos
    }

    private fun readFully(ch: FileChannel, bb: ByteBuffer, at: Long) {
        var pos = at
        while (bb.hasRemaining()) {
            val n = ch.read(bb, pos)
            if (n <= 0) throw PayloadException("the payload ends too early (truncated download?)")
            pos += n
        }
    }
}
