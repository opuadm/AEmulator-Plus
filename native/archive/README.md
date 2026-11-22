# libaemuarc - one native library for every archive/compression format

The importer used to unpack with Java libraries (tukaani XZ, Commons Compress bzip2/lz4/7z, Brotli dec, `GZIPInputStream`).
They are several times slower than C on a phone. `libaemuarc.so` replaces all of them behind two Java entry points:

| Java (`app.aemu.archive`) | Formats |
|---|---|
| `ArcStreams.gzip/zlib/xz/lzma/bzip2/lz4/lz4Legacy/lz4Any/zstd/brotli(InputStream)`, `decodeAll`, `lz4Block`, `byName` | gzip (concatenated members), zlib, raw deflate, xz (concatenated, padding, all filters, CRC32/64/SHA-256), lzma-alone, bzip2 (concatenated), LZ4 frame (concatenated, skippable), LZ4 legacy (`lz4 -l`, see below), Zstandard, Brotli, raw LZ4 block |
| `SevenZReaders.open(FileChannel)` | 7z: LZMA, LZMA2, PPMd, BZip2, Deflate, Copy, BCJ/ARM/ARM64/ARMT/PPC/SPARC/IA64/RISC-V/Delta filters, **streamed** (a multi-GB solid archive is never held in memory); BCJ2 folders are unpacked in memory up to 512 MiB |

| `RarReaders.open(FileChannel)` | RAR: 3.x / 4.x (LZ, PPMd var.H, the standard VM filters E8 / E8E9 / Delta / RGB / Audio / Itanium), 5.x / 7.x (LZ, E8 / E8E9 / ARM / Delta filters), stored entries of every version, SFX stubs, solid archives, streamed in one forward pass |

If the library cannot be loaded (JVM unit tests) or a 7z uses something it does not know (AES, other coders), the same Java
call falls back to the old libraries, so nothing is lost.

### LZ4 legacy (`ARC_LZ4L`, `src/c_lz4l.c`)
The old `lz4 -l` stream format (magic `02 21 4C 18`; Linux initramfs, ramdisks of some 4.x kernels), which the regular LZ4 frame decoder
rejects. Independent blocks of up to 8 MiB, concatenated streams and skippable frames are accepted; zero padding behind the stream
(page-aligned ramdisks, any length) ends it; input that stops inside a block or size field is `ARC_E_TRUNCATED`; an LZ4 *frame* magic is
`ARC_E_DATA` (use `ARC_LZ4F`). `ArcStreams.lz4Any` picks frame or legacy from the first four bytes. There is no Commons Compress
support for this format, so the JVM fallback is `Lz4LegacyInputStream`.

### RAR (`src/arc_rar.c`)
`arcrar_open / select / read` work like the 7z functions: entries are read forward, a solid entry decodes the entries before it that were
skipped (from the start of its chain), CRC32 is checked at the end of every entry. The decoder window is sized from the archive's
dictionary and total size, capped by `mem_cap`. Written from the format descriptions, no unrar code; the PPMd model is the SDK's `Ppmd7a`.
`arcrar_open` returns `ARC_E_UNSUPPORTED` (so the importer falls back to junrar) for RAR 1.x / 2.x compression, encrypted data or headers,
volume sets, custom RAR3 VM programs and entries of unknown size. RAR 5 links and file copies are not listed. BLAKE2sp hashes (`rar -ht`)
are not verified, CRC32 is. The RAR3 filter programs are recognised by length and CRC; the Delta and E8E9 programs are covered by the
fixtures, the E8, Itanium, RGB and Audio ones are implemented from the format but have no fixture (a wrong result there fails the CRC check
and the importer then uses junrar).

## Layout
- `include/aemuarc.h` the C API (push decoders `arc_dec_*`, 7z `arc7z_*`), `src/` the implementation, `src/arc_jni.c` the JNI glue.
- `third_party/` vendored, unmodified upstream sources (decoders only): zlib 1.3.1, bzip2 1.0.8, lz4 1.10.0, zstd 1.5.7,
  brotli 1.1.0, 7-Zip 25.01 `C/` (LZMA SDK: LZMA, LZMA2, xz, PPMd, 7z headers, filters). Licenses are next to each.
- `jni/` a copy of `jni.h` / `jni_md.h` (OpenJDK, GPLv2+CE) for building without an NDK.

## Build
```sh
sh native/archive/build.sh android   # arm64-v8a with zig cc, no NDK; writes app/src/main/jniLibs/arm64-v8a/libaemuarc.so (checked in)
sh native/archive/tests/run.sh       # host build (plain + AddressSanitizer) and the test suite
```
`build.sh android` needs only `python3 -m pip install ziglang` (like `native/hwchal/build-zig.sh`): it compiles with musl headers and links
against a stand-in `libc.so`, so the result imports only plain libc functions (memcpy, malloc, free, memset, memmove, calloc, realloc,
getauxval, bcmp) that bionic provides. 16 KiB page alignment, only `JNI_OnLoad` and `arc_*` are exported.

## Tests
`tests/run_rar_tests.py` reads 42 RAR fixtures (RAR 3/4/5, PPMd, filters, solid, unicode, SFX-free; some damaged on purpose) in four read patterns and fuzzes corrupted copies under ASan. `tests/run.sh` compares against the reference tools (xz, gzip, bzip2, python lz4/zstandard/brotli, and 7-Zip 25 `7zz` when
`Z7=/path/to/7zz` or it is on PATH): every codec at awkward chunk sizes (1 byte in/out ... 1 MiB), concatenated and padded streams,
truncation, corruption (never crashes, checksums catch flips), and 7z in 18 layouts (solid / non-solid / multi-block, all coders and
filters, BCJ2, empty files, unicode names, forward / reverse / partial reading, encrypted and damaged archives).
