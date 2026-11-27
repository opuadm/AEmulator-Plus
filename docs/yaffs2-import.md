# YAFFS2 recovery backups

Sunset.21 supports clean `mkyaffs2image`-style recovery snapshots with 2048+64
or 4096+128 page/spare geometry, little- or big-endian header/tag integers.
This is an original Kotlin reader, not a port of the YAFFS filesystem driver.

Import `system.yaffs2.img` directly, or put it with `boot.img` and optionally
`recovery.img` in ZIP, 7z or TAR and import that archive. An enclosing backup
folder inside an archive is supported. No new folder-picker workflow is added.
Including boot.img gives the importer the firmware's own ramdisk/init metadata.

Other `.yaffs2.img` entries such as data, cache and `.android_secure` are ignored:
firmware import does not restore your applications, accounts or personal data.
The backup source is read-only. The rootfs is built in a new VM directory and
removed by the existing import failure/cancellation cleanup if parsing fails.

Files, directories, symlink aliases and executable permission bits are retained;
hard links are resolved and copied as files. The existing guest-root-relative
symlink conversion is used. Special objects are not created on the host.
UID/GID ownership, timestamps and exact POSIX permission masks are not restored
(the importer uses its existing Android-host permission policy).

Page-only NAND dumps (no spare/OOB area at all, pages in physical order with obsolete
pages still in place, e.g. an HTC engineering dump with 4096-byte chunks) are read
through the checkpoint YAFFS2 writes at unmount: the reader finds its marker in the
first or last 32 MiB, decodes the object records and chunk maps (versions 4-8, 32- or
64-bit sizes, either byte order), then finds chunk size and the dump's first device
chunk by checking that the recorded header chunks really hold object headers. A dump
without a valid checkpoint, or one that skipped bad blocks (so chunk numbers no longer
match positions), is not recognised. Whatever was never part of the checkpoint (e.g.
files deleted before it was written) does not appear.

Limits: at most 1,000,000 pages, 100,000 object headers and 256 parent/link hops.
Corrupt/missing chunks, repeated headers/chunks, unsafe names, duplicate paths,
parent/hard-link cycles, missing parents and truncated pages fail explicitly.
This reader does not replay raw NAND journals or handle YAFFS1, in-band tags,
extended packed headers, ECC repair, shrink/shadow records or 64-bit file sizes.
Unsupported layouts are rejected, not silently restored as an incomplete ROM.
Successful unpacking is not a guarantee that the emulator can boot that firmware.

Format references:
[YAFFS object header](https://github.com/Aleph-One-Ltd/yaffs2/blob/master/core/yaffs_guts.h),
[packed YAFFS2 tags](https://github.com/Aleph-One-Ltd/yaffs2/blob/master/core/yaffs_packedtags2.c).
No firmware or decompiled vendor source is bundled or published.

The optional local regression fixture uses `AEMU_YAFFS2_FIXTURE` to point at the
user-supplied CWM system snapshot (MD5 `51824642b12922d01c83cf40ddd795fa`).
It verifies every file's chunk completeness and copies all regular/hard-link
file bytes to a counting sink, then checks the system build.prop/framework tree.
It is not included in Git or release APKs.
