package app.aemu.archive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens RAR archives with libaemuarc: RAR 3.x / 4.x (LZ, PPMd, standard filters), RAR 5.x / 7.x, stored entries, SFX stubs, solid
 * archives, decoded in one forward pass without holding a file in memory.
 *
 * {@link #open} throws {@link UnsupportedOperationException} when the library is missing or cannot take this archive (RAR 1.x / 2.x
 * compression, encryption, volume sets, custom RAR3 filters); the caller then uses another reader (junrar).
 */
public final class RarReaders {
    private RarReaders() {}

    /** The channel is only read from, never closed or repositioned by the native reader. */
    public static SevenZReader open(FileChannel ch) throws IOException {
        if (!NativeArc.AVAILABLE) throw new UnsupportedOperationException("libaemuarc is not available");
        try {
            return new Native(ch);
        } catch (UnsatisfiedLinkError e) {
            throw new UnsupportedOperationException("libaemuarc has no RAR reader", e);
        }
    }

    private static final class Native implements SevenZReader {
        private final List<Entry> entries = new ArrayList<>();
        private long h;
        private int idx = -1;

        Native(FileChannel ch) throws IOException {
            Object src = new Object() {
                @SuppressWarnings("unused") // called from native code
                public int readAt(long pos, ByteBuffer dst) throws IOException { return ch.read(dst, pos); }
            };
            h = NativeArc.rarOpen(src, ch.size(), 1L << 30);
            try {
                int n = NativeArc.rarCount(h);
                long[] info = new long[2];
                for (int i = 0; i < n; i++) {
                    NativeArc.rarInfo(h, i, info);
                    entries.add(new Entry(NativeArc.rarName(h, i), info[0], info[1] != 0));
                }
            } catch (RuntimeException | Error e) {
                NativeArc.rarClose(h); h = 0;
                throw e;
            }
        }

        @Override public List<Entry> entries() { return entries; }

        @Override public Entry nextEntry() throws IOException {
            if (h == 0) throw new IOException("Stream closed");
            if (++idx >= entries.size()) { idx = entries.size(); return null; }
            NativeArc.rarSelect(h, idx);
            return entries.get(idx);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (h == 0) throw new IOException("Stream closed");
            if (idx < 0 || idx >= entries.size()) return -1;
            if (len == 0) return 0;
            return NativeArc.rarRead(h, b, off, len);
        }

        @Override public void close() { long x = h; h = 0; if (x != 0) NativeArc.rarClose(x); }
    }
}
