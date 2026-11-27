package app.aemu.core
import java.io.*
import java.security.MessageDigest

fun check(c: Boolean, m: String) { if (!c) { println("FAIL: $m"); System.exit(1) } else println("ok: $m") }
fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

object TestCodecs : PayloadOta.Codecs {
    override fun brotli(i: InputStream): InputStream = app.aemu.archive.ArcStreams.brotli(i)
    override fun xz(i: InputStream): InputStream = throw IOException("xz not available in the test")
    override fun bzip2(i: InputStream): InputStream {
        val pb = ProcessBuilder("bzip2", "-dc").redirectErrorStream(false).start()
        val data = i.readBytes()
        val t = Thread { pb.outputStream.use { it.write(data) } }; t.start()
        val out = pb.inputStream.readBytes(); t.join(); pb.waitFor()
        if (pb.exitValue() != 0) throw IOException("bzip2 failed")
        return ByteArrayInputStream(out)
    }
}

fun main() {
    PayloadOta.codecs = TestCodecs
    // ---- synthetic: end to end, payload inside a window of a file
    val pf = File("data/payload.bin")
    val p = PayloadOta.Payload(pf, 100, pf.length() - 100)
    val m = PayloadOta.readManifest(p)
    check(m.blockSize == 4096 && m.minorVersion == 7 && m.partitions.size == 1 && m.incremental, "manifest parsed (incremental)")
    check(PayloadOta.unsupported(m).isEmpty(), "all operations supported")
    check(PayloadOta.verifyFile(p, File("data/payload_properties.txt").readText()), "FILE_HASH verified")
    val out = File("data/out.img"); out.delete()
    PayloadOta.apply(p, m, m.partitions[0], File("data/old.img"), out)
    check(out.length() == File("data/new.img").length(), "new image has the right size")
    check(sha(out) == sha(File("data/new.img")), "new image is bit-identical (SOURCE_COPY + hole + BROTLI_BSDIFF + REPLACE + ZERO)")

    // ---- a damaged base is reported, not patched
    val bad = File("data/old_bad.img"); val b = File("data/old.img").readBytes(); b[20 * 4096 + 7] = (b[20 * 4096 + 7] + 1).toByte(); bad.writeBytes(b)
    val r = runCatching { PayloadOta.apply(p, m, m.partitions[0], bad, File("data/out2.img")) }
    check(r.exceptionOrNull() is PayloadException && r.exceptionOrNull()!!.message!!.contains("differs from the build"), "modified base rejected: " + r.exceptionOrNull()?.message)
    val r2 = runCatching { PayloadOta.apply(p, m, m.partitions[0], null, File("data/out3.img")) }
    check(r2.exceptionOrNull() is PayloadException, "missing base rejected: " + r2.exceptionOrNull()?.message)

    // ---- a damaged payload is reported
    val corrupt = File("data/payload_corrupt.bin"); val pb = pf.readBytes(); pb[pb.size - 50] = (pb[pb.size - 50] + 1).toByte(); corrupt.writeBytes(pb)
    val r3 = runCatching { PayloadOta.verifyFile(PayloadOta.Payload(corrupt, 100, corrupt.length() - 100), File("data/payload_properties.txt").readText()) }
    check(r3.exceptionOrNull() is PayloadException, "corrupt payload file rejected: " + r3.exceptionOrNull()?.message)
    val r4 = runCatching { PayloadOta.apply(PayloadOta.Payload(corrupt, 100, corrupt.length() - 100), m, m.partitions[0], File("data/old.img"), File("data/out4.img")) }
    check(r4.exceptionOrNull() is PayloadException, "corrupt op data rejected: " + r4.exceptionOrNull()?.message)

    // ---- the real MIUI / HyperOS incremental
    val rp = PayloadOta.Payload(File("/home/claude/ota/payload.bin"))
    val rm = PayloadOta.readManifest(rp)
    println("real: ${rm.partitions.size} partitions, minor ${rm.minorVersion}, patch ${rm.securityPatch}, ts ${rm.maxTimestamp}")
    check(rm.partitions.size == 40 && rm.minorVersion > 0 && rm.incremental, "real manifest: 40 partitions, incremental")
    check(PayloadOta.unsupported(rm).isEmpty(), "real OTA: every operation type is supported")
    check(PayloadOta.verifyFile(rp, File("/home/claude/ota/payload_properties.txt").readText()), "real payload FILE_HASH verified")
    val sys = rm.partition("system")!!
    check(sys.newSize == 956436480L && sys.oldSize == 956436480L && sys.oldSha256 != null, "system partition info")
    // every real BSDF2 patch through the decoder against a random old image (output cannot be checked without the base, but length and stream handling can)
    val rnd = java.util.Random(1)
    val fake = File("data/fake_old.img"); RandomAccessFile(fake, "rw").use { it.setLength(0) }
    var n = 0; var bytes = 0L
    java.nio.channels.FileChannel.open(File("/home/claude/ota/payload.bin").toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
        for (part in rm.partitions) for (op in part.ops) if (op.type == PayloadOta.BROTLI_BSDIFF && op.dataLength < 4_000_000 && op.src.sumOf { it.blocks } < 2048) {
            val bb = java.nio.ByteBuffer.allocate(op.dataLength.toInt()); ch.read(bb, rm.dataOffset + op.dataOffset)
            val srcBytes = op.src.sumOf { it.blocks } * 4096
            val old = ByteArray(srcBytes.toInt()).also { rnd.nextBytes(it) }
            val tmp = File("data/fake_old.img"); tmp.writeBytes(old)
            java.nio.channels.FileChannel.open(tmp.toPath(), java.nio.file.StandardOpenOption.READ).use { oc ->
                val src = PayloadOta.Source(oc, listOf(PayloadOta.Extent(0, op.src.sumOf { it.blocks })), 4096)
                val bos = ByteArrayOutputStream()
                val made = PayloadOta.bspatch(src, bb.array(), bos, "real ${part.name}")
                val expect = op.dst.sumOf { it.blocks } * 4096
                if (made > expect || made != bos.size().toLong()) { println("FAIL: ${part.name} patch made $made, extents $expect"); System.exit(1) }
                n++; bytes += made
            }
        }
    }
    check(n > 300, "decoded + ran $n real BROTLI_BSDIFF patches ($bytes bytes) without error")
    // a real op against a wrong base must be reported by its hash
    val boot = rm.partition("boot")!!
    val wrong = File("data/wrong_boot.img"); wrong.writeBytes(ByteArray(67108864 / 16)) // short, zero base
    val r5 = runCatching { PayloadOta.apply(rp, rm, boot, wrong, File("data/out5.img")) }
    check(r5.exceptionOrNull() is PayloadException && r5.exceptionOrNull()!!.message!!.contains("differs from the build"), "real boot op on a wrong base: " + r5.exceptionOrNull()?.message)
    println("ALL OK")
}
