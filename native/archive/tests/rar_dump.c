/* rar_dump FILE...: lists every entry of each RAR archive and reads it completely (CRC32 is checked by the library). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "aemuarc.h"
static int64_t rd(void *ctx, uint64_t pos, void *buf, size_t len) {
    FILE *f = (FILE *)ctx;
    if (fseek(f, (long)pos, SEEK_SET)) return -1;
    return (int64_t)fread(buf, 1, len, f);
}
int main(int argc, char **argv) {
    int bad = 0, k;
    static uint8_t buf[1 << 20];
    size_t chunk = getenv("CHUNK") ? (size_t)atol(getenv("CHUNK")) : sizeof buf;
    int reverse = getenv("REVERSE") != NULL, skip_odd = getenv("SKIPODD") != NULL;
    for (k = 1; k < argc; k++) {
        FILE *f = fopen(argv[k], "rb");
        arc_src s; int err = 0; arcrar *a; uint32_t n, i;
        if (!f) { printf("%s: cannot open\n", argv[k]); bad++; continue; }
        fseek(f, 0, SEEK_END); s.size = (uint64_t)ftell(f); s.read_at = rd; s.ctx = f;
        a = arcrar_open(&s, 0, &err);
        if (!a) { printf("%s: open failed: %s (%d)\n", argv[k], arc_strerror(err), err); fclose(f); if (err != ARC_E_UNSUPPORTED) bad++; continue; }
        n = arcrar_count(a);
        printf("%s: %u entries\n", argv[k], n);
        for (uint32_t j = 0; j < n; j++) {
            i = reverse ? n - 1 - j : j;
            uint64_t size; int dir; size_t nl = arcrar_name16(a, i, NULL); uint16_t *nm = malloc((nl + 1) * 2); char name[512]; size_t q;
            arcrar_name16(a, i, nm);
            for (q = 0; q < nl && q < 500; q++) name[q] = nm[q] < 128 ? (char)nm[q] : '?';
            name[q] = 0; free(nm);
            arcrar_info(a, i, &size, &dir);
            if (skip_odd && (i & 1)) { printf("  %-40s %10llu (skipped)\n", name, (unsigned long long)size); continue; }
            int r = arcrar_select(a, i);
            if (r) { printf("  %-40s select failed: %s\n", name, arc_strerror(r)); bad++; break; }
            uint64_t total = 0; int64_t got;
            while ((got = arcrar_read(a, buf, chunk)) > 0) total += (uint64_t)got;
            if (got < 0) { printf("  %-40s READ ERROR %s after %llu bytes\n", name, arc_strerror((int)got), (unsigned long long)total); bad++; if (!dir) break; continue; }
            if (total != size) { printf("  %-40s SIZE MISMATCH %llu != %llu\n", name, (unsigned long long)total, (unsigned long long)size); bad++; }
            else printf("  %-40s %10llu %s\n", name, (unsigned long long)size, dir ? "dir" : "ok");
        }
        arcrar_close(a); fclose(f);
    }
    printf(bad ? "FAILED: %d problem(s)\n" : "all ok\n", bad);
    return bad != 0;
}
