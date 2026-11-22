/*
 * RAR reader for libaemuarc: RAR 3.x / 4.x (unpack versions 29 and 36: LZ, PPMd var.H, the standard VM filters) and
 * RAR 5.x / 7.x (LZ with E8 / E8E9 / ARM / Delta filters), stored entries of every version, SFX stubs, solid archives.
 * Written from the published format descriptions; no unrar source is used (its licence does not allow it in a GPL app).
 *
 * Not handled (arcrar_open fails with ARC_E_UNSUPPORTED so that the caller can use another reader): RAR 1.x / 2.x
 * compression, encryption, multi-volume sets, custom (non-standard) RAR3 VM programs, entries of unknown size.
 * BLAKE2sp file hashes are not verified (CRC32 is).
 */
#include <stdlib.h>
#include <string.h>
#include "arc_internal.h"
#include "zlib.h"
#include "Alloc.h"
#include "Ppmd7.h"

#define NC5 306
#define DC5 64
#define DCX5 80
#define LDC5 16
#define RC5 44
#define NC3 299
#define DC3 60
#define LDC3 17
#define RC3 28
#define BC 20
#define QB 10
#define MAXSYM 306
#define IBCAP (1u << 18)
#define PAD 256
#define MAX_FILTER5 0x400000u
#define MAX_PENDING 8192
#define MAX_PRG3 8192
#define SIG_SCAN (4u << 20)

enum { F_DELTA = 0, F_E8, F_E8E9, F_ARM, F_ITANIUM, F_RGB, F_AUDIO };

typedef struct {
    uint16_t quick[1 << QB];
    uint32_t lim[17], fl[17];
    uint16_t pos[17];
    uint16_t sym[MAXSYM];
} huff;

typedef struct {
    uint64_t pack_pos, pack_size, unp_size, dict;
    uint32_t crc; uint16_t *name; uint32_t name_len;
    uint8_t has_crc, is_dir, stored, solid;
} rar_ent;

typedef struct { uint64_t start; uint32_t len; uint8_t type; uint32_t r0, r1; } filt;
typedef struct { uint8_t std; uint32_t oldlen; } prg3;
typedef struct { IByteIn vt; void *owner; } ppm_in;

typedef struct arcrar {
    arc_src src; uint64_t mem_cap;
    int fmt;                       /* 4 = RAR 1.5-4.x container, 5 = RAR 5+ */
    rar_ent *e; uint32_t n, cap;
    uint64_t total_unp, max_dict;
    int has_comp;
    /* current entry */
    int cur; uint64_t produced; uint32_t crc; int done; int dec_valid; int64_t dec_last, dec_chain;
    uint64_t st_pos, st_left;      /* stored entry */
    /* input */
    uint8_t *ib; size_t ilen, bitpos; uint64_t sb, pk_pos, pk_left; int ieof, ishort, pov;
    /* window and output */
    uint8_t *win; size_t wsize, wmask; uint64_t abs, flushed, file_abs0, limit_abs;
    uint8_t *oq; size_t oq_len, oq_pos, oq_cap;
    uint8_t *fb1, *fb2; size_t fbcap;
    filt *f; size_t nf, fcap;
    int file_end;
    /* LZ state */
    huff LD, DD, LDD, RD, BD;
    uint64_t od[4]; uint32_t last_len;
    int extra_dist;
    /* RAR5 block */
    int tables_read, blk_last, tbl_present; uint64_t blk_end; unsigned blk_bits;
    /* RAR3 */
    int tables_read3, block_ppm, esc; uint8_t old_table[NC3 + DC3 + LDC3 + RC3]; unsigned prev_low, low_rep;
    prg3 *prg; uint32_t nprg, last_filter;
    CPpmd7 ppm; ppm_in pin; int ppm_ready, ppm_alloc;
    uint8_t scratch[1 << 16];
} arcrar;

/* ---------- small helpers ---------- */

static inline uint64_t ld_be64(const uint8_t *p) {
    uint64_t v; memcpy(&v, p, 8);
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
    return v;
#else
    return __builtin_bswap64(v);
#endif
}
static inline uint32_t ld32(const uint8_t *p) { return p[0] | (p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
static inline void st32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

static int rd_full(const arcrar *a, uint64_t pos, void *buf, size_t len) {
    size_t got = 0;
    while (got < len) {
        int64_t n = a->src.read_at(a->src.ctx, pos + got, (uint8_t *)buf + got, len - got);
        if (n < 0) return ARC_E_IO;
        if (n == 0) return ARC_E_TRUNCATED;
        got += (size_t)n;
    }
    return 0;
}

static int vint(const uint8_t *p, size_t n, size_t *pos, uint64_t *v) {
    uint64_t r = 0; unsigned sh = 0;
    while (*pos < n && sh < 70) {
        uint8_t b = p[(*pos)++];
        if (sh < 64) r |= (uint64_t)(b & 0x7f) << sh;
        if (!(b & 0x80)) { *v = r; return 0; }
        sh += 7;
    }
    return -1;
}

static int utf8_valid(const uint8_t *s, size_t n) {
    size_t i = 0;
    while (i < n) {
        uint8_t c = s[i];
        size_t k = c < 0x80 ? 0 : (c >> 5) == 6 ? 1 : (c >> 4) == 14 ? 2 : (c >> 3) == 30 ? 3 : 9;
        if (k == 9 || i + k >= n + (k == 0)) { if (k) return 0; }
        for (size_t j = 1; j <= k; j++) if (i + j >= n || (s[i + j] & 0xc0) != 0x80) return 0;
        i += k + 1;
    }
    return 1;
}

/* UTF-8 (or, when it is not valid UTF-8, Latin-1) to UTF-16 */
static int to16(const uint8_t *s, size_t n, uint16_t **out, uint32_t *outn) {
    uint16_t *w = (uint16_t *)malloc((n + 1) * sizeof(uint16_t));
    size_t o = 0, i = 0;
    if (!w) return ARC_E_MEM;
    if (!utf8_valid(s, n)) { for (i = 0; i < n; i++) w[o++] = s[i]; }
    else while (i < n) {
        uint32_t c = s[i];
        if (c < 0x80) { w[o++] = (uint16_t)c; i++; }
        else if ((c >> 5) == 6) { w[o++] = (uint16_t)(((c & 31) << 6) | (s[i + 1] & 63)); i += 2; }
        else if ((c >> 4) == 14) { w[o++] = (uint16_t)(((c & 15) << 12) | ((s[i + 1] & 63) << 6) | (s[i + 2] & 63)); i += 3; }
        else {
            uint32_t cp = ((c & 7) << 18) | ((s[i + 1] & 63) << 12) | ((s[i + 2] & 63) << 6) | (s[i + 3] & 63);
            cp -= 0x10000; w[o++] = (uint16_t)(0xd800 + (cp >> 10)); w[o++] = (uint16_t)(0xdc00 + (cp & 0x3ff)); i += 4;
        }
    }
    *out = w; *outn = (uint32_t)o;
    return 0;
}

/* RAR 3/4 names with the Unicode flag: "oem bytes" 0 "encoded wide name" */
static int name4(const uint8_t *nm, size_t n, int uni, uint16_t **out, uint32_t *outn) {
    size_t z = 0;
    if (!uni) return to16(nm, n, out, outn);
    while (z < n && nm[z]) z++;
    if (z >= n) return to16(nm, n, out, outn);
    {
        const uint8_t *enc = nm + z + 1; size_t es = n - z - 1, ep = 0, dp = 0;
        uint16_t *w = (uint16_t *)malloc((n + es + 2) * sizeof(uint16_t));
        unsigned flags = 0, fbits = 0, hi;
        if (!w) return ARC_E_MEM;
        hi = es ? enc[ep++] : 0;
        while (ep < es) {
            if (fbits == 0) { flags = enc[ep++]; fbits = 8; if (ep > es) break; }
            switch (flags >> 6) {
            case 0: if (ep >= es) goto end; w[dp++] = enc[ep++]; break;
            case 1: if (ep >= es) goto end; w[dp++] = (uint16_t)(enc[ep++] + (hi << 8)); break;
            case 2: if (ep + 1 >= es) goto end; w[dp++] = (uint16_t)(enc[ep] + (enc[ep + 1] << 8)); ep += 2; break;
            default: {
                unsigned len;
                if (ep >= es) goto end;
                len = enc[ep++];
                if (len & 0x80) {
                    unsigned corr; if (ep >= es) goto end;
                    corr = enc[ep++];
                    for (len = (len & 0x7f) + 2; len > 0 && dp < z; len--, dp++) w[dp] = (uint16_t)(((nm[dp] + corr) & 0xff) + (hi << 8));
                } else for (len += 2; len > 0 && dp < z; len--, dp++) w[dp] = nm[dp];
            } }
            flags = (flags << 2) & 0xff; fbits -= 2;
        }
    end:
        *out = w; *outn = (uint32_t)dp;
        return 0;
    }
}

static int add_ent(arcrar *a, const rar_ent *e) {
    if (a->n == a->cap) {
        uint32_t nc = a->cap ? a->cap * 2 : 64;
        rar_ent *ne;
        if (nc > (1u << 24)) return ARC_E_DATA;
        ne = (rar_ent *)realloc(a->e, nc * sizeof *ne);
        if (!ne) return ARC_E_MEM;
        a->e = ne; a->cap = nc;
    }
    a->e[a->n++] = *e;
    return 0;
}

/* ---------- container: RAR 1.5 - 4.x ---------- */

static int parse4(arcrar *a, uint64_t pos) {
    uint64_t size = a->src.size;
    uint8_t h7[7];
    for (;;) {
        unsigned type, flags, hsize; uint32_t add = 0;
        int r;
        if (pos + 7 > size) break;
        if ((r = rd_full(a, pos, h7, 7)) != 0) return r;
        type = h7[2]; flags = h7[3] | (h7[4] << 8); hsize = h7[5] | (h7[6] << 8);
        if (hsize < 7) return ARC_E_DATA;
        if (type == 0x7b) break;
        if (type == 0x73) {
            if (flags & 0x80) return ARC_E_UNSUPPORTED;   /* encrypted headers */
            if (flags & 0x01) return ARC_E_UNSUPPORTED;   /* a volume of a set */
            pos += hsize; continue;
        }
        if (type == 0x74) {
            uint8_t *h = (uint8_t *)malloc(hsize);
            rar_ent e; uint64_t pack, unp; unsigned nlen, off, ver, method; int uni;
            if (!h) return ARC_E_MEM;
            if ((r = rd_full(a, pos, h, hsize)) != 0) { free(h); return r; }
            if (hsize < 32) { free(h); return ARC_E_DATA; }
            /* RAR 1.x / 2.x writers differ in what the header CRC covers; those entries are refused below anyway */
            if (h[24] >= 29 && ((crc32(0, h + 2, hsize - 2) & 0xffff) != (unsigned)(h[0] | (h[1] << 8)))) { free(h); return ARC_E_DATA; }
            memset(&e, 0, sizeof e);
            pack = ld32(h + 7); unp = ld32(h + 11);
            ver = h[24]; method = h[25]; nlen = h[26] | (h[27] << 8); off = 32;
            if (flags & 0x100) { if (hsize < 40) { free(h); return ARC_E_DATA; } pack |= (uint64_t)ld32(h + 32) << 32; unp |= (uint64_t)ld32(h + 36) << 32; off = 40; }
            if (off + nlen > hsize) { free(h); return ARC_E_DATA; }
            if (flags & 0x04) { free(h); return ARC_E_UNSUPPORTED; }          /* password */
            if (flags & 0x03) { free(h); return ARC_E_UNSUPPORTED; }          /* split across volumes */
            uni = (flags & 0x200) != 0;
            e.is_dir = (flags & 0xe0) == 0xe0;
            e.stored = method == 0x30;
            e.solid = (flags & 0x10) != 0;
            e.crc = ld32(h + 16); e.has_crc = 1;
            e.pack_pos = pos + hsize; e.pack_size = pack; e.unp_size = e.is_dir ? 0 : unp;
            e.dict = 4u << 20;
            if (!e.is_dir && !e.stored) {
                if ((ver != 29 && ver != 36) || method < 0x31 || method > 0x35) { free(h); return ARC_E_UNSUPPORTED; }
                if (unp == 0xffffffffu && !(flags & 0x100)) { free(h); return ARC_E_UNSUPPORTED; }
                a->has_comp = 1;
            }
            if (e.stored && !e.is_dir) e.unp_size = pack;     /* a stored entry is as long as its data */
            if (e.pack_pos + e.pack_size > size) { free(h); return ARC_E_TRUNCATED; }
            if ((r = name4(h + off, nlen, uni, &e.name, &e.name_len)) != 0) { free(h); return r; }
            free(h);
            if ((r = add_ent(a, &e)) != 0) { free(e.name); return r; }
            if (!e.is_dir) a->total_unp += e.unp_size;
            pos = e.pack_pos + e.pack_size;
            continue;
        }
        if (flags & 0x8000) { uint8_t b4[4]; if ((r = rd_full(a, pos + 7, b4, 4)) != 0) return r; add = ld32(b4); }
        pos += (uint64_t)hsize + add;
    }
    return 0;
}

/* ---------- container: RAR 5 / 7 ---------- */

static int parse5(arcrar *a, uint64_t pos) {
    uint64_t size = a->src.size;
    for (;;) {
        uint8_t hd[16]; size_t vl = 0, got, p = 0; uint64_t hs, type, hflags, extra = 0, dsize = 0;
        uint8_t *h; size_t total; int r;
        if (pos + 5 > size) break;
        got = (size_t)(size - pos < sizeof hd ? size - pos : sizeof hd);
        if ((r = rd_full(a, pos, hd, got)) != 0) return r;
        { size_t q = 4; if (vint(hd, got, &q, &hs) != 0) return ARC_E_DATA; vl = q - 4; }
        if (hs == 0 || hs > (2u << 20)) return ARC_E_DATA;
        total = 4 + vl + (size_t)hs;
        if (pos + total > size) return ARC_E_TRUNCATED;
        h = (uint8_t *)malloc(total);
        if (!h) return ARC_E_MEM;
        if ((r = rd_full(a, pos, h, total)) != 0) { free(h); return r; }
        if (crc32(0, h + 4, (uInt)(total - 4)) != ld32(h)) { free(h); return ARC_E_DATA; }
        p = 4 + vl;
        if (vint(h, total, &p, &type) || vint(h, total, &p, &hflags)) { free(h); return ARC_E_DATA; }
        if (hflags & 1) { if (vint(h, total, &p, &extra)) { free(h); return ARC_E_DATA; } }
        if (hflags & 2) { if (vint(h, total, &p, &dsize)) { free(h); return ARC_E_DATA; } }
        if (extra > total - p) { free(h); return ARC_E_DATA; }
        if (type == 4) { free(h); return ARC_E_UNSUPPORTED; }                 /* encrypted headers */
        if (type == 5) { free(h); break; }
        if (type == 1) {
            uint64_t af = 0;
            if (vint(h, total, &p, &af)) { free(h); return ARC_E_DATA; }
            if (af & 1) { free(h); return ARC_E_UNSUPPORTED; }              /* a volume of a set */
        } else if (type == 2) {
            rar_ent e; uint64_t ff, unp, attr, ci, os, nl; uint32_t crc = 0; int has_crc = 0, redirect = 0; size_t ep;
            memset(&e, 0, sizeof e);
            if (hflags & 0x18) { free(h); return ARC_E_UNSUPPORTED; }       /* continued from / into another volume */
            if (vint(h, total, &p, &ff) || vint(h, total, &p, &unp) || vint(h, total, &p, &attr)) { free(h); return ARC_E_DATA; }
            if (ff & 2) { if (p + 4 > total) { free(h); return ARC_E_DATA; } p += 4; }
            if (ff & 4) { if (p + 4 > total) { free(h); return ARC_E_DATA; } crc = ld32(h + p); p += 4; has_crc = 1; }
            if (vint(h, total, &p, &ci) || vint(h, total, &p, &os) || vint(h, total, &p, &nl)) { free(h); return ARC_E_DATA; }
            if (nl > total - p || p + nl > total - extra) { free(h); return ARC_E_DATA; }
            e.is_dir = (ff & 1) != 0;
            if ((ff & 8) && !e.is_dir) { free(h); return ARC_E_UNSUPPORTED; } /* unknown size */
            { size_t nb = (size_t)nl; if ((r = to16(h + p, nb, &e.name, &e.name_len)) != 0) { free(h); return r; } }
            ep = total - (size_t)extra;
            while (ep < total) {                                           /* extra area records */
                uint64_t rs, rt; size_t rp = ep, re;
                if (vint(h, total, &rp, &rs)) break;
                re = rp + (size_t)rs; if (re > total || rs == 0) break;
                { size_t q = rp; if (vint(h, re, &q, &rt) == 0) {
                    if (rt == 1) { free(e.name); free(h); return ARC_E_UNSUPPORTED; }      /* file encryption */
                    if (rt == 5) redirect = 1;                                              /* symlink, hard link, file copy */
                } }
                ep = re;
            }
            e.solid = (ci >> 6) & 1;
            e.stored = ((ci >> 7) & 7) == 0;
            e.crc = crc; e.has_crc = (uint8_t)has_crc;
            e.pack_pos = pos + total; e.pack_size = dsize; e.unp_size = e.is_dir ? 0 : unp;
            if (!e.is_dir && e.stored) e.unp_size = dsize;
            if (!e.is_dir && !e.stored) {
                unsigned ver = (unsigned)(ci & 0x3f); uint64_t dl = (ci >> 10) & 0x1f, d, fr = (ci >> 15) & 0x1f;
                if (ver > 1) { free(e.name); free(h); return ARC_E_UNSUPPORTED; }
                d = (uint64_t)0x20000 << dl;
                if (ver == 1) d += (d / 32) * fr;
                e.dict = d;
                if (d > a->max_dict) a->max_dict = d;
                if (ver == 1) a->has_comp = 2; else if (!a->has_comp) a->has_comp = 1;
            }
            if (e.pack_pos + e.pack_size > size) { free(e.name); free(h); return ARC_E_TRUNCATED; }
            if (redirect) free(e.name);
            else {
                if ((r = add_ent(a, &e)) != 0) { free(e.name); free(h); return r; }
                if (!e.is_dir) a->total_unp += e.unp_size;
            }
        }
        free(h);
        pos += total + dsize;
        (void)extra; (void)p;
    }
    return 0;
}

/* ---------- Huffman ---------- */

static int huff_build(huff *h, const uint8_t *len, unsigned n) {
    unsigned cnt[17], nxt[17], i, l, start = 0;
    uint32_t code = 0;
    memset(cnt, 0, sizeof cnt);
    for (i = 0; i < n; i++) cnt[len[i] & 15]++;
    cnt[0] = 0;
    memset(h->quick, 0, sizeof h->quick);
    for (l = 1; l <= 15; l++) {
        h->fl[l] = code << (16 - l);
        h->pos[l] = (uint16_t)start; nxt[l] = start;
        if (code + cnt[l] > (1u << l)) return -1;
        h->lim[l] = (code + cnt[l]) << (16 - l);
        code = (code + cnt[l]) << 1;
        start += cnt[l];
    }
    for (i = 0; i < n; i++) {
        l = len[i] & 15;
        if (!l) continue;
        {
            unsigned idx = nxt[l]++;
            h->sym[idx] = (uint16_t)i;
            if (l <= QB) {
                uint32_t c = (h->fl[l] >> (16 - l)) + (idx - h->pos[l]), lo = c << (QB - l), k, cntq = 1u << (QB - l);
                for (k = 0; k < cntq; k++) h->quick[lo + k] = (uint16_t)((l << 9) | i);
            }
        }
    }
    return 0;
}

static inline uint32_t peek32(const arcrar *a) {
    size_t p = a->bitpos >> 3; unsigned s = a->bitpos & 7;
    return (uint32_t)((ld_be64(a->ib + p) << s) >> 32);
}
static inline uint32_t getbits(arcrar *a, unsigned n) { uint32_t v = peek32(a) >> (32 - n); a->bitpos += n; return v; }

static inline int huff_dec(arcrar *a, const huff *h) {
    uint32_t v = peek32(a) >> 16;
    uint16_t e = h->quick[v >> (16 - QB)];
    unsigned l;
    if (e) { a->bitpos += e >> 9; return e & 511; }
    for (l = QB + 1; l <= 15; l++)
        if (v < h->lim[l]) { a->bitpos += l; return h->sym[h->pos[l] + ((v - h->fl[l]) >> (16 - l))]; }
    return -1;
}

/* ---------- input ---------- */

static int refill(arcrar *a) {
    size_t from = a->bitpos >> 3, rem;
    if (from > a->ilen) from = a->ilen;
    rem = a->ilen - from;
    if (from) { memmove(a->ib, a->ib + from, rem); a->bitpos -= from * 8; a->sb += from; a->ilen = rem; }
    while (!a->ieof && a->ilen < IBCAP) {
        uint64_t want = IBCAP - a->ilen; int64_t n;
        if (want > a->pk_left) want = a->pk_left;
        if (!want) { a->ieof = 1; break; }
        n = a->src.read_at(a->src.ctx, a->pk_pos, a->ib + a->ilen, (size_t)want);
        if (n < 0) return ARC_E_IO;
        if (n == 0) { a->ieof = 1; a->ishort = 1; break; }
        a->ilen += (size_t)n; a->pk_pos += (uint64_t)n; a->pk_left -= (uint64_t)n;
    }
    if (a->pk_left == 0) a->ieof = 1;
    memset(a->ib + a->ilen, 0, PAD);
    return 0;
}
static inline int need(arcrar *a) { return (!a->ieof && (a->bitpos >> 3) + 64 > a->ilen) ? refill(a) : 0; }
static inline int over(const arcrar *a) { return a->pov || (a->ieof && a->bitpos > a->ilen * 8); }
static inline uint64_t abs_bit(const arcrar *a) { return a->sb * 8 + a->bitpos; }

/* ---------- window, output queue, filters ---------- */

static inline void win_put(arcrar *a, uint8_t c) { a->win[a->abs & a->wmask] = c; a->abs++; }

static int copy_match(arcrar *a, uint64_t len, uint64_t dist) {
    size_t m = a->wmask, dst, src, i;
    if (dist == 0 || dist > a->abs || dist > a->wsize) return ARC_E_DATA;
    dst = (size_t)(a->abs & m); src = (size_t)((a->abs - dist) & m);
    a->abs += len;
    if (dst + len <= a->wsize && src + len <= a->wsize) {
        uint8_t *w = a->win;
        if (dist >= len) memcpy(w + dst, w + src, (size_t)len);
        else for (i = 0; i < len; i++) w[dst + i] = w[src + i];
    } else for (i = 0; i < len; i++) a->win[(dst + i) & m] = a->win[(src + i) & m];
    return 0;
}

static int oq_room(arcrar *a, size_t n) {
    if (a->oq_len + n <= a->oq_cap) return 0;
    if (a->oq_pos) { memmove(a->oq, a->oq + a->oq_pos, a->oq_len - a->oq_pos); a->oq_len -= a->oq_pos; a->oq_pos = 0; }
    if (a->oq_len + n > a->oq_cap) {
        size_t nc = a->oq_cap ? a->oq_cap : (1u << 20);
        uint8_t *nb;
        while (nc < a->oq_len + n) nc *= 2;
        nb = (uint8_t *)realloc(a->oq, nc);
        if (!nb) return ARC_E_MEM;
        a->oq = nb; a->oq_cap = nc;
    }
    return 0;
}

static void win_read(const arcrar *a, uint64_t from, size_t n, uint8_t *dst) {
    size_t s = (size_t)(from & a->wmask), first = a->wsize - s;
    if (first >= n) memcpy(dst, a->win + s, n);
    else { memcpy(dst, a->win + s, first); memcpy(dst + first, a->win, n - first); }
}

/* raw window bytes [from, to) to the queue, cut at the end of the entry */
static int out_win(arcrar *a, uint64_t from, uint64_t to) {
    int r;
    if (to > a->limit_abs) to = a->limit_abs;
    if (to <= from) return 0;
    if ((r = oq_room(a, (size_t)(to - from))) != 0) return r;
    win_read(a, from, (size_t)(to - from), a->oq + a->oq_len);
    a->oq_len += (size_t)(to - from);
    return 0;
}

static void flt_e8(uint8_t *d, uint32_t n, uint32_t foff, int e9, int mod) {
    uint32_t cur = 0, cmp2 = e9 ? 0xe9 : 0xe8;
    if (n < 4) return;
    n -= 4;
    while (cur < n) {
        uint8_t c = d[cur++];
        if (c == 0xe8 || c == cmp2) {
            uint32_t off = cur + foff, addr;
            if (mod) off %= 0x1000000u;
            addr = ld32(d + cur);
            if (addr & 0x80000000u) { if (((addr + off) & 0x80000000u) == 0) st32(d + cur, addr + 0x1000000u); }
            else if (((addr - 0x1000000u) & 0x80000000u) != 0) st32(d + cur, addr - off);
            cur += 4;
        }
    }
}
static void flt_arm(uint8_t *d, uint32_t n, uint32_t foff) {
    uint32_t cur;
    for (cur = 0; cur + 3 < n; cur += 4) {
        uint8_t *p = d + cur;
        if (p[3] == 0xeb) {
            uint32_t off = p[0] + (uint32_t)p[1] * 0x100 + (uint32_t)p[2] * 0x10000;
            off -= (foff + cur) / 4;
            p[0] = (uint8_t)off; p[1] = (uint8_t)(off >> 8); p[2] = (uint8_t)(off >> 16);
        }
    }
}
static void flt_delta(const uint8_t *src, uint8_t *dst, uint32_t n, unsigned ch) {
    unsigned c; uint32_t sp = 0, dp;
    for (c = 0; c < ch; c++) { uint8_t prev = 0; for (dp = c; dp < n; dp += ch) dst[dp] = (prev = (uint8_t)(prev - src[sp++])); }
}
static void flt_rgb(const uint8_t *src, uint8_t *dst, uint32_t n, uint32_t width, uint32_t posr) {
    unsigned c; uint32_t i;
    if (posr > 2) { memcpy(dst, src, n); return; }
    for (c = 0; c < 3; c++) {
        uint32_t prev = 0;
        for (i = c; i < n; i += 3) {
            uint32_t pred;
            if (i >= width) {
                uint32_t up = dst[i - width], ul = dst[i - width - 3];
                int pa, pb, pc;
                pred = prev + up - ul;
                pa = abs((int)(pred - prev)); pb = abs((int)(pred - up)); pc = abs((int)(pred - ul));
                pred = (pa <= pb && pa <= pc) ? prev : (pb <= pc ? up : ul);
            } else pred = prev;
            dst[i] = (uint8_t)(prev = (uint8_t)(pred - *src++));
        }
    }
    for (i = posr; i + 2 < n; i += 3) { uint8_t g = dst[i + 1]; dst[i] = (uint8_t)(dst[i] + g); dst[i + 2] = (uint8_t)(dst[i + 2] + g); }
}
static void flt_audio(const uint8_t *src, uint8_t *dst, uint32_t n, unsigned ch) {
    unsigned c;
    for (c = 0; c < ch; c++) {
        uint32_t prev = 0, pdelta = 0, dif[7], i, bc = 0;
        int d1 = 0, d2 = 0, d3, k1 = 0, k2 = 0, k3 = 0;
        memset(dif, 0, sizeof dif);
        for (i = c; i < n; i += ch, bc++) {
            uint32_t pred, cur; int d, j;
            d3 = d2; d2 = (int)(pdelta - (uint32_t)d1); d1 = (int)pdelta;
            pred = 8 * prev + (uint32_t)(k1 * d1 + k2 * d2 + k3 * d3);
            pred = (pred >> 3) & 0xff;
            cur = *src++;
            pred -= cur;
            dst[i] = (uint8_t)pred;
            pdelta = (uint32_t)(int)(signed char)(uint8_t)(pred - prev);
            prev = pred & 0xff;
            d = ((signed char)cur) << 3;
            dif[0] += abs(d); dif[1] += abs(d - d1); dif[2] += abs(d + d1); dif[3] += abs(d - d2);
            dif[4] += abs(d + d2); dif[5] += abs(d - d3); dif[6] += abs(d + d3);
            if ((bc & 0x1f) == 0) {
                uint32_t mind = dif[0], nm = 0;
                dif[0] = 0;
                for (j = 1; j < 7; j++) { if (dif[j] < mind) { mind = dif[j]; nm = (uint32_t)j; } dif[j] = 0; }
                switch (nm) {
                case 1: if (k1 >= -16) k1--; break;
                case 2: if (k1 < 16) k1++; break;
                case 3: if (k2 >= -16) k2--; break;
                case 4: if (k2 < 16) k2++; break;
                case 5: if (k3 >= -16) k3--; break;
                case 6: if (k3 < 16) k3++; break;
                }
            }
        }
    }
}
static uint32_t itan_get(const uint8_t *d, int bitpos, int cnt) {
    int ia = bitpos / 8, ib = bitpos & 7;
    uint32_t f = d[ia] | ((uint32_t)d[ia + 1] << 8) | ((uint32_t)d[ia + 2] << 16) | ((uint32_t)d[ia + 3] << 24);
    f >>= ib;
    return f & (0xffffffffu >> (32 - cnt));
}
static void itan_set(uint8_t *d, uint32_t v, int bitpos, int cnt) {
    int ia = bitpos / 8, ib = bitpos & 7, i;
    uint32_t mask = ~((0xffffffffu >> (32 - cnt)) << ib);
    v <<= ib;
    for (i = 0; i < 4; i++) { d[ia + i] &= (uint8_t)mask; d[ia + i] |= (uint8_t)v; mask = (mask >> 8) | 0xff000000u; v >>= 8; }
}
static void flt_itanium(uint8_t *d, uint32_t n, uint32_t foff) {
    static const uint8_t masks[16] = { 4, 4, 6, 6, 0, 0, 7, 7, 4, 4, 0, 0, 4, 4, 0, 0 };
    uint32_t cur = 0;
    if (n < 21) return;
    foff >>= 4;
    while (cur < n - 21) {
        int b = (d[0] & 0x1f) - 0x10;
        if (b >= 0) {
            uint8_t cm = masks[b];
            if (cm) {
                int i;
                for (i = 0; i <= 2; i++) if (cm & (1 << i)) {
                    int sp = i * 41 + 5;
                    if (itan_get(d, sp + 37, 4) == 5) {
                        uint32_t off = itan_get(d, sp + 13, 20);
                        itan_set(d, (off - foff) & 0xfffff, sp + 13, 20);
                    }
                }
            }
        }
        d += 16; cur += 16; foff++;
    }
}

static int fb_room(arcrar *a, size_t n) {
    if (n <= a->fbcap) return 0;
    {
        uint8_t *x = (uint8_t *)realloc(a->fb1, n), *y;
        if (!x) return ARC_E_MEM;
        a->fb1 = x;
        y = (uint8_t *)realloc(a->fb2, n);
        if (!y) return ARC_E_MEM;
        a->fb2 = y; a->fbcap = n;
    }
    return 0;
}

/* one filter over [in, in+n); the result is in place or in the other buffer */
static uint8_t *run_filter(arcrar *a, const filt *f, uint8_t *in, uint32_t n, uint64_t foff) {
    uint8_t *other = in == a->fb1 ? a->fb2 : a->fb1;
    uint32_t fo = (uint32_t)foff;
    switch (f->type) {
    case F_E8: case F_E8E9: flt_e8(in, n, fo, f->type == F_E8E9, a->fmt == 5); return in;
    case F_ARM: flt_arm(in, n, fo); return in;
    case F_ITANIUM: flt_itanium(in, n, fo); return in;
    case F_DELTA: if (f->r0 == 0 || f->r0 > 32) return in; flt_delta(in, other, n, f->r0); return other;
    case F_RGB: if (f->r0 < 3) return in; flt_rgb(in, other, n, f->r0 - 3, f->r1); return other;
    case F_AUDIO: if (f->r0 == 0 || f->r0 > 4) return in; flt_audio(in, other, n, f->r0); return other;
    }
    return in;
}

static void drop_filter(arcrar *a) { if (a->nf) { memmove(a->f, a->f + 1, (a->nf - 1) * sizeof(filt)); a->nf--; } }

/* hands everything that is complete to the output queue; keeps the part that waits for an unfinished filter block */
static int flush_out(arcrar *a, int final) {
    int r;
    for (;;) {
        uint64_t cur = a->flushed;
        if (cur >= a->limit_abs) { a->flushed = a->abs; a->nf = 0; break; }
        if (cur >= a->abs) break;
        if (a->nf && a->f[0].start < cur) { drop_filter(a); continue; }
        if (a->nf == 0 || a->f[0].start > cur) {
            uint64_t end = a->abs;
            if (a->nf && a->f[0].start < end) end = a->f[0].start;
            if ((r = out_win(a, cur, end)) != 0) return r;
            a->flushed = end;
            continue;
        } else {
            filt fl = a->f[0]; uint64_t fend = fl.start + fl.len; uint8_t *buf; uint64_t foff = cur - a->file_abs0;
            if (fl.len == 0) { drop_filter(a); continue; }
            if (fend > a->abs) {
                if (!final) break;
                if ((r = out_win(a, cur, a->abs)) != 0) return r;
                a->flushed = a->abs; a->nf = 0; break;
            }
            if ((r = fb_room(a, fl.len)) != 0) return r;
            win_read(a, cur, fl.len, a->fb1);
            buf = a->fb1;
            drop_filter(a);
            buf = run_filter(a, &fl, buf, fl.len, foff);
            while (a->nf && a->f[0].start == fl.start && a->f[0].len == fl.len) {   /* several filters over one block */
                filt nx = a->f[0]; drop_filter(a);
                buf = run_filter(a, &nx, buf, fl.len, foff);
            }
            {
                uint64_t take = fl.len;
                if (cur + take > a->limit_abs) take = a->limit_abs - cur;
                if ((r = oq_room(a, (size_t)take)) != 0) return r;
                memcpy(a->oq + a->oq_len, buf, (size_t)take); a->oq_len += (size_t)take;
            }
            a->flushed = fend;
        }
    }
    return 0;
}

static int add_filter(arcrar *a, const filt *fl) {
    size_t k;
    if (fl->len > a->wsize - 16384 || fl->len > MAX_FILTER5) return ARC_E_DATA;
    if (a->nf >= MAX_PENDING) return ARC_E_DATA;
    if (a->nf == a->fcap) {
        size_t nc = a->fcap ? a->fcap * 2 : 16;
        filt *nf = (filt *)realloc(a->f, nc * sizeof(filt));
        if (!nf) return ARC_E_MEM;
        a->f = nf; a->fcap = nc;
    }
    for (k = a->nf; k > 0 && a->f[k - 1].start > fl->start; k--) a->f[k] = a->f[k - 1];
    a->f[k] = *fl; a->nf++;
    return 0;
}

/* ---------- RAR 5 decoder ---------- */

static int read_block5(arcrar *a) {
    const uint8_t *p; unsigned flags, bc, i; uint32_t size = 0; uint64_t start;
    int r;
    a->bitpos = (a->bitpos + 7) & ~(size_t)7;
    if ((r = need(a)) != 0) return r;
    if (over(a)) return ARC_E_TRUNCATED;
    p = a->ib + (a->bitpos >> 3);
    flags = p[0]; bc = ((flags >> 3) & 3) + 1;
    if (bc == 4) return ARC_E_DATA;
    for (i = 0; i < bc; i++) size |= (uint32_t)p[2 + i] << (8 * i);
    if (((0x5a ^ flags ^ size ^ (size >> 8) ^ (size >> 16)) & 0xff) != p[1]) return ARC_E_DATA;
    a->bitpos += (2 + bc) * 8;
    a->blk_bits = (flags & 7) + 1; a->blk_last = (flags & 0x40) != 0; a->tbl_present = (flags & 0x80) != 0;
    start = a->sb + (a->bitpos >> 3);
    a->blk_end = (start + size) * 8 - 8 + a->blk_bits;
    return 0;
}

static int read_tables5(arcrar *a) {
    uint8_t bl[BC], tab[NC5 + DCX5 + LDC5 + RC5];
    unsigned i, tsz = NC5 + (a->extra_dist ? DCX5 : DC5) + LDC5 + RC5, dc = a->extra_dist ? DCX5 : DC5;
    int r;
    if (!a->tbl_present) return a->tables_read ? 0 : ARC_E_DATA;
    if ((r = need(a)) != 0) return r;
    for (i = 0; i < BC; i++) {
        unsigned len = getbits(a, 4);
        if (len == 15) {
            unsigned z = getbits(a, 4);
            if (z == 0) bl[i] = 15;
            else { z += 2; while (z-- > 0 && i < BC) bl[i++] = 0; i--; }
        } else bl[i] = (uint8_t)len;
    }
    if (huff_build(&a->BD, bl, BC)) return ARC_E_DATA;
    for (i = 0; i < tsz;) {
        int num; unsigned cnt;
        if ((r = need(a)) != 0) return r;
        if (over(a)) return ARC_E_TRUNCATED;
        num = huff_dec(a, &a->BD);
        if (num < 0) return ARC_E_DATA;
        if (num < 16) { tab[i++] = (uint8_t)num; continue; }
        if (num == 16 || num == 18) cnt = getbits(a, 3) + 3; else cnt = getbits(a, 7) + 11;
        if (num < 18) {
            if (i == 0) return ARC_E_DATA;
            while (cnt-- > 0 && i < tsz) { tab[i] = tab[i - 1]; i++; }
        } else while (cnt-- > 0 && i < tsz) tab[i++] = 0;
    }
    if (huff_build(&a->LD, tab, NC5) || huff_build(&a->DD, tab + NC5, dc) ||
        huff_build(&a->LDD, tab + NC5 + dc, LDC5) || huff_build(&a->RD, tab + NC5 + dc + LDC5, RC5)) return ARC_E_DATA;
    a->tables_read = 1;
    return 0;
}

static inline uint32_t slot_len5(arcrar *a, unsigned slot) {
    unsigned lb; uint32_t len = 2;
    if (slot < 8) { lb = 0; len += slot; } else { lb = slot / 4 - 1; len += (4 | (slot & 3)) << lb; }
    if (lb) len += getbits(a, lb);
    return len;
}
static inline uint64_t bits64(arcrar *a, unsigned n) {
    if (n <= 24) return n ? getbits(a, n) : 0;
    { uint64_t hi = getbits(a, 24); return (hi << (n - 24)) | getbits(a, n - 24); }
}
static uint32_t filter_data5(arcrar *a) {
    unsigned cnt = getbits(a, 2) + 1, i; uint32_t v = 0;
    for (i = 0; i < cnt; i++) v += getbits(a, 8) << (8 * i);
    return v;
}

static int run5(arcrar *a, uint64_t goal) {
    int r;
    while (a->abs < goal) {
        int sym;
        if ((r = need(a)) != 0) return r;
        if (over(a)) return ARC_E_TRUNCATED;
        while (abs_bit(a) >= a->blk_end) {
            if (a->blk_last) { a->file_end = 1; return ARC_END; }
            if ((r = read_block5(a)) != 0) return r;
            if ((r = read_tables5(a)) != 0) return r;
            if ((r = need(a)) != 0) return r;
            if (over(a)) return ARC_E_TRUNCATED;
        }
        sym = huff_dec(a, &a->LD);
        if (sym < 0) return ARC_E_DATA;
        if (sym < 256) { win_put(a, (uint8_t)sym); continue; }
        if (sym >= 262) {
            uint32_t len = slot_len5(a, (unsigned)sym - 262);
            unsigned ds, db; uint64_t dist = 1;
            int x = huff_dec(a, &a->DD);
            if (x < 0) return ARC_E_DATA;
            ds = (unsigned)x;
            if (ds < 4) { db = 0; dist += ds; } else { db = ds / 2 - 1; dist += (uint64_t)(2 | (ds & 1)) << db; }
            if (db) {
                if (db >= 4) {
                    if (db > 4) dist += bits64(a, db - 4) << 4;
                    x = huff_dec(a, &a->LDD);
                    if (x < 0) return ARC_E_DATA;
                    dist += (unsigned)x;
                } else dist += getbits(a, db);
            }
            if (dist > 0x100) { len++; if (dist > 0x2000) { len++; if (dist > 0x40000) len++; } }
            a->od[3] = a->od[2]; a->od[2] = a->od[1]; a->od[1] = a->od[0]; a->od[0] = dist;
            a->last_len = len;
            if ((r = copy_match(a, len, dist)) != 0) return r;
            continue;
        }
        if (sym == 256) {
            filt fl; unsigned t; uint32_t bs, bl;
            bs = filter_data5(a); bl = filter_data5(a);
            if (bl > MAX_FILTER5) bl = 0;
            t = getbits(a, 3);
            memset(&fl, 0, sizeof fl);
            fl.start = a->abs + bs; fl.len = bl; fl.type = (uint8_t)t;
            if (t == 0) fl.r0 = getbits(a, 5) + 1;
            if (t > 3) { if (bl) return ARC_E_UNSUPPORTED; continue; }
            if ((r = add_filter(a, &fl)) != 0) return r;
            continue;
        }
        if (sym == 257) { if (a->last_len && (r = copy_match(a, a->last_len, a->od[0])) != 0) return r; continue; }
        {
            unsigned dn = (unsigned)sym - 258, i; uint64_t dist = a->od[dn]; int x; uint32_t len;
            for (i = dn; i > 0; i--) a->od[i] = a->od[i - 1];
            a->od[0] = dist;
            x = huff_dec(a, &a->RD);
            if (x < 0) return ARC_E_DATA;
            len = slot_len5(a, (unsigned)x);
            a->last_len = len;
            if ((r = copy_match(a, len, dist)) != 0) return r;
        }
    }
    return ARC_OK;
}

/* ---------- RAR 3 / 4 decoder ---------- */

static Byte ppm_read(IByteInPtr p) {
    arcrar *a = (arcrar *)((const ppm_in *)p)->owner;
    size_t i = a->bitpos >> 3;
    if (i + 8 > a->ilen && !a->ieof) { refill(a); i = a->bitpos >> 3; }
    if (i >= a->ilen) { a->pov = 1; return 0; }
    a->bitpos += 8;
    return a->ib[i];
}
static int rbyte(arcrar *a) {
    size_t i;
    if (need(a)) return -1;
    i = a->bitpos >> 3;
    if (i >= a->ilen) { a->pov = 1; return -1; }
    a->bitpos += 8;
    return a->ib[i];
}

static int ppm_init(arcrar *a) {
    int b0 = rbyte(a), reset, mb = 0;
    if (b0 < 0) return ARC_E_TRUNCATED;
    reset = (b0 & 0x20) != 0;
    if (reset) { mb = rbyte(a); if (mb < 0) return ARC_E_TRUNCATED; }
    else if (!a->ppm_ready) return ARC_E_DATA;
    if (b0 & 0x40) { int e = rbyte(a); if (e < 0) return ARC_E_TRUNCATED; a->esc = e; }
    if (reset) {
        unsigned order = (unsigned)(b0 & 0x1f) + 1;
        uint64_t sz = ((uint64_t)mb + 1) << 20;
        if (order > 16) order = 16 + (order - 16) * 3;
        if (order == 1) return ARC_E_DATA;
        if (sz > a->mem_cap) return ARC_E_UNSUPPORTED;
        if (!a->ppm_alloc || a->ppm.Size != (UInt32)sz) {
            if (a->ppm_alloc) Ppmd7_Free(&a->ppm, &g_Alloc);
            a->ppm_alloc = 0;
            if (!Ppmd7_Alloc(&a->ppm, (UInt32)sz, &g_Alloc)) return ARC_E_MEM;
            a->ppm_alloc = 1;
        }
        Ppmd7_Init(&a->ppm, order);
        a->ppm_ready = 1;
    }
    a->pin.vt.Read = ppm_read; a->pin.owner = a;
    a->ppm.rc.dec.Stream = &a->pin.vt;
    if (!Ppmd7a_RangeDec_Init(&a->ppm.rc.dec)) return ARC_E_DATA;
    return a->pov ? ARC_E_TRUNCATED : 0;
}

static int read_tables3(arcrar *a) {
    uint8_t bl[BC], tab[NC3 + DC3 + LDC3 + RC3];
    unsigned bf, i; int r;
    a->bitpos = (a->bitpos + 7) & ~(size_t)7;
    if ((r = need(a)) != 0) return r;
    bf = peek32(a) >> 16;
    if (bf & 0x8000) { a->block_ppm = 1; return ppm_init(a); }
    a->block_ppm = 0; a->prev_low = 0; a->low_rep = 0;
    if (!(bf & 0x4000)) memset(a->old_table, 0, sizeof a->old_table);
    a->bitpos += 2;
    for (i = 0; i < BC; i++) {
        unsigned len = getbits(a, 4);
        if (len == 15) {
            unsigned z = getbits(a, 4);
            if (z == 0) bl[i] = 15;
            else { z += 2; while (z-- > 0 && i < BC) bl[i++] = 0; i--; }
        } else bl[i] = (uint8_t)len;
    }
    if (huff_build(&a->BD, bl, BC)) return ARC_E_DATA;
    for (i = 0; i < sizeof tab;) {
        int num; unsigned cnt;
        if ((r = need(a)) != 0) return r;
        if (over(a)) return ARC_E_TRUNCATED;
        num = huff_dec(a, &a->BD);
        if (num < 0) return ARC_E_DATA;
        if (num < 16) { tab[i] = (uint8_t)((num + a->old_table[i]) & 15); i++; continue; }
        if (num == 16 || num == 18) cnt = getbits(a, 3) + 3; else cnt = getbits(a, 7) + 11;
        if (num < 18) { if (i > 0) while (cnt-- > 0 && i < sizeof tab) { tab[i] = tab[i - 1]; i++; } }
        else while (cnt-- > 0 && i < sizeof tab) tab[i++] = 0;
    }
    a->tables_read3 = 1;
    if (over(a)) return ARC_E_TRUNCATED;
    if (huff_build(&a->LD, tab, NC3) || huff_build(&a->DD, tab + NC3, DC3) ||
        huff_build(&a->LDD, tab + NC3 + DC3, LDC3) || huff_build(&a->RD, tab + NC3 + DC3 + LDC3, RC3)) return ARC_E_DATA;
    memcpy(a->old_table, tab, sizeof tab);
    return 0;
}

typedef struct { const uint8_t *b; size_t n, pos; } vmbr;
static unsigned vm_get16(const vmbr *v) {
    size_t i = v->pos >> 3; unsigned s = v->pos & 7; uint32_t x = 0;
    int k;
    for (k = 0; k < 3; k++) x = (x << 8) | (i + k < v->n ? v->b[i + k] : 0);
    return (x >> (8 - s)) & 0xffff;
}
static uint32_t vm_data(vmbr *v) {
    unsigned d = vm_get16(v);
    switch (d & 0xc000) {
    case 0: v->pos += 6; return (d >> 10) & 0xf;
    case 0x4000:
        if ((d & 0x3c00) == 0) { v->pos += 14; return 0xffffff00u | ((d >> 2) & 0xff); }
        v->pos += 10; return (d >> 6) & 0xff;
    case 0x8000: v->pos += 2; d = vm_get16(v); v->pos += 16; return d;
    default: { uint32_t x; v->pos += 2; x = (uint32_t)vm_get16(v) << 16; v->pos += 16; x |= vm_get16(v); v->pos += 16; return x; }
    }
}

static int std_filter_of(const uint8_t *code, size_t n) {
    static const struct { uint32_t len, crc; uint8_t t; } tab[] = {
        { 53, 0xad576887u, F_E8 }, { 57, 0x3cd7e57eu, F_E8E9 }, { 120, 0x3769893fu, F_ITANIUM },
        { 29, 0x0e06077du, F_DELTA }, { 149, 0x1c2c5dc8u, F_RGB }, { 216, 0xbc85e701u, F_AUDIO } };
    uint8_t x = 0; size_t i; uint32_t c;
    for (i = 1; i < n; i++) x ^= code[i];
    if (!n || x != code[0]) return -1;
    c = (uint32_t)crc32(0, code, (uInt)n);
    for (i = 0; i < sizeof tab / sizeof tab[0]; i++) if (tab[i].len == n && tab[i].crc == c) return tab[i].t + 1;
    return 0;
}

static int add_vm(arcrar *a, unsigned first, const uint8_t *code, size_t clen) {
    vmbr b; unsigned fpos, i; int isnew; uint32_t bs, len, R[7]; filt fl;
    b.b = code; b.n = clen; b.pos = 0;
    if (first & 0x80) {
        uint32_t v = vm_data(&b);
        if (v == 0) { a->nprg = 0; a->last_filter = 0; a->nf = 0; fpos = 0; } else fpos = v - 1;
    } else fpos = a->last_filter;
    if (fpos > a->nprg) return ARC_E_DATA;
    a->last_filter = fpos;
    isnew = fpos == a->nprg;
    if (isnew) {
        if (fpos > MAX_PRG3) return ARC_E_DATA;
        if (a->nprg == 0 || (a->nprg & (a->nprg - 1)) == 0) {
            prg3 *np = (prg3 *)realloc(a->prg, (a->nprg ? a->nprg * 2 : 1) * sizeof(prg3));
            if (!np) return ARC_E_MEM;
            a->prg = np;
        }
        a->prg[a->nprg].std = 0; a->prg[a->nprg].oldlen = 0; a->nprg++;
    }
    bs = vm_data(&b);
    if (first & 0x40) bs += 258;
    len = (first & 0x20) ? vm_data(&b) : a->prg[fpos].oldlen;
    a->prg[fpos].oldlen = len;
    memset(R, 0, sizeof R); R[3] = 0x3c000; R[4] = len;
    if (first & 0x10) {
        unsigned mask = vm_get16(&b) >> 9;
        b.pos += 7;
        for (i = 0; i < 7; i++) if (mask & (1u << i)) R[i] = vm_data(&b);
    }
    if (isnew) {
        uint32_t sz = vm_data(&b); size_t k; uint8_t *prog; int st;
        if (sz >= 0x10000 || sz == 0 || b.pos / 8 + sz > clen + 1) return ARC_E_DATA;
        prog = (uint8_t *)malloc(sz);
        if (!prog) return ARC_E_MEM;
        for (k = 0; k < sz; k++) { prog[k] = (uint8_t)(vm_get16(&b) >> 8); b.pos += 8; }
        st = std_filter_of(prog, sz);
        free(prog);
        if (st < 0) return ARC_E_DATA;
        a->prg[fpos].std = (uint8_t)st;
    }
    if (a->prg[fpos].std == 0) return ARC_E_UNSUPPORTED;   /* a custom VM program */
    memset(&fl, 0, sizeof fl);
    fl.start = a->abs + bs; fl.len = len; fl.type = (uint8_t)(a->prg[fpos].std - 1); fl.r0 = R[0]; fl.r1 = R[1];
    if (len > 0x3c000) return ARC_E_DATA;
    return add_filter(a, &fl);
}

static int vm_from_lz(arcrar *a) {
    unsigned first, len, i; uint8_t *code; int r, c;
    if ((r = need(a)) != 0) return r;
    first = getbits(a, 8);
    len = (first & 7) + 1;
    if (len == 7) len = getbits(a, 8) + 7;
    else if (len == 8) len = getbits(a, 16);
    if (len == 0) return ARC_E_DATA;
    code = (uint8_t *)malloc(len);
    if (!code) return ARC_E_MEM;
    for (i = 0; i < len; i++) {
        if ((r = need(a)) != 0 || over(a)) { free(code); return r ? r : ARC_E_TRUNCATED; }
        c = (int)getbits(a, 8);
        code[i] = (uint8_t)c;
    }
    r = add_vm(a, first, code, len);
    free(code);
    return r;
}
static int vm_from_ppm(arcrar *a) {
    int fb = Ppmd7a_DecodeSymbol(&a->ppm), r;
    unsigned len, i; uint8_t *code;
    if (fb < 0) return ARC_E_DATA;
    len = ((unsigned)fb & 7) + 1;
    if (len == 7) { int b1 = Ppmd7a_DecodeSymbol(&a->ppm); if (b1 < 0) return ARC_E_DATA; len = (unsigned)b1 + 7; }
    else if (len == 8) {
        int b1 = Ppmd7a_DecodeSymbol(&a->ppm), b2;
        if (b1 < 0) return ARC_E_DATA;
        b2 = Ppmd7a_DecodeSymbol(&a->ppm);
        if (b2 < 0) return ARC_E_DATA;
        len = (unsigned)b1 * 256 + (unsigned)b2;
    }
    if (len == 0) return ARC_E_DATA;
    code = (uint8_t *)malloc(len);
    if (!code) return ARC_E_MEM;
    for (i = 0; i < len; i++) {
        int c = Ppmd7a_DecodeSymbol(&a->ppm);
        if (c < 0 || a->pov) { free(code); return ARC_E_DATA; }
        code[i] = (uint8_t)c;
    }
    r = add_vm(a, (unsigned)fb, code, len);
    free(code);
    return r;
}

static int run3(arcrar *a, uint64_t goal) {
    static const uint8_t LDec[28] = { 0,1,2,3,4,5,6,7,8,10,12,14,16,20,24,28,32,40,48,56,64,80,96,112,128,160,192,224 };
    static const uint8_t LBit[28] = { 0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5 };
    static const uint8_t SDDec[8] = { 0,4,8,16,32,64,128,192 };
    static const uint8_t SDBit[8] = { 2,2,3,4,5,6,6,6 };
    static const uint8_t DBitCounts[19] = { 4,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,14,0,12 };
    static uint32_t DDec[DC3]; static uint8_t DBit[DC3]; static int dinit;
    int r;
    if (!dinit) {
        int dist = 0, bl = 0, slot = 0, i, j;
        for (i = 0; i < 19; i++, bl++) for (j = 0; j < DBitCounts[i]; j++, slot++, dist += 1 << bl) { DDec[slot] = (uint32_t)dist; DBit[slot] = (uint8_t)bl; }
        dinit = 1;
    }
    while (a->abs < goal) {
        if ((r = need(a)) != 0) return r;
        if (over(a)) { a->file_end = 1; return ARC_E_TRUNCATED; }
        if (a->block_ppm) {
            int ch = Ppmd7a_DecodeSymbol(&a->ppm);
            if (ch < 0) return ARC_E_DATA;
            if (ch == a->esc) {
                int nx = Ppmd7a_DecodeSymbol(&a->ppm);
                if (nx < 0) return ARC_E_DATA;
                if (nx == 0) { if ((r = read_tables3(a)) != 0) return r; continue; }
                if (nx == 2) { a->file_end = 1; return ARC_END; }
                if (nx == 3) { if ((r = vm_from_ppm(a)) != 0) return r; continue; }
                if (nx == 4) {
                    uint32_t dist = 0, len = 0; int k;
                    for (k = 0; k < 4; k++) {
                        int c = Ppmd7a_DecodeSymbol(&a->ppm);
                        if (c < 0) return ARC_E_DATA;
                        if (k == 3) len = (uint32_t)c; else dist = (dist << 8) + (uint32_t)c;
                    }
                    if ((r = copy_match(a, len + 32, (uint64_t)dist + 2)) != 0) return r;
                    continue;
                }
                if (nx == 5) {
                    int len = Ppmd7a_DecodeSymbol(&a->ppm);
                    if (len < 0) return ARC_E_DATA;
                    if ((r = copy_match(a, (uint64_t)len + 4, 1)) != 0) return r;
                    continue;
                }
                /* nx == 1: the escape byte itself */
            }
            win_put(a, (uint8_t)ch);
            continue;
        } else {
            int num = huff_dec(a, &a->LD);
            if (num < 0) return ARC_E_DATA;
            if (num < 256) { win_put(a, (uint8_t)num); continue; }
            if (num >= 271) {
                uint32_t len; unsigned bits, ds; uint32_t dist; int x;
                num -= 271;
                len = LDec[num] + 3u;
                if ((bits = LBit[num]) > 0) len += getbits(a, bits);
                x = huff_dec(a, &a->DD);
                if (x < 0 || x >= DC3) return ARC_E_DATA;
                ds = (unsigned)x;
                dist = DDec[ds] + 1;
                if ((bits = DBit[ds]) > 0) {
                    if (ds > 9) {
                        if (bits > 4) dist += getbits(a, bits - 4) << 4;
                        if (a->low_rep > 0) { a->low_rep--; dist += a->prev_low; }
                        else {
                            int ld = huff_dec(a, &a->LDD);
                            if (ld < 0) return ARC_E_DATA;
                            if (ld == 16) { a->low_rep = 15 - 1; dist += a->prev_low; }
                            else { dist += (unsigned)ld; a->prev_low = (unsigned)ld; }
                        }
                    } else dist += getbits(a, bits);
                }
                if (dist >= 0x2000) { len++; if (dist >= 0x40000) len++; }
                a->od[3] = a->od[2]; a->od[2] = a->od[1]; a->od[1] = a->od[0]; a->od[0] = dist;
                a->last_len = len;
                if ((r = copy_match(a, len, dist)) != 0) return r;
                continue;
            }
            if (num == 256) {
                unsigned bf = peek32(a) >> 16; int newfile = 0, newtable;
                if (bf & 0x8000) { newtable = 1; a->bitpos += 1; }
                else { newfile = 1; newtable = (bf & 0x4000) != 0; a->bitpos += 2; }
                a->tables_read3 = !newtable;
                if (newfile) { a->file_end = 1; return ARC_END; }
                if ((r = read_tables3(a)) != 0) return r;
                continue;
            }
            if (num == 257) { if ((r = vm_from_lz(a)) != 0) return r; continue; }
            if (num == 258) { if (a->last_len && (r = copy_match(a, a->last_len, a->od[0])) != 0) return r; continue; }
            if (num < 263) {
                unsigned dn = (unsigned)num - 259, i, bits; uint64_t dist = a->od[dn]; int x; uint32_t len;
                for (i = dn; i > 0; i--) a->od[i] = a->od[i - 1];
                a->od[0] = dist;
                x = huff_dec(a, &a->RD);
                if (x < 0 || x >= 28) return ARC_E_DATA;
                len = LDec[x] + 2u;
                if ((bits = LBit[x]) > 0) len += getbits(a, bits);
                a->last_len = len;
                if ((r = copy_match(a, len, dist)) != 0) return r;
                continue;
            }
            if (num < 272) {
                unsigned k = (unsigned)num - 263, bits; uint32_t dist = SDDec[k] + 1u;
                if ((bits = SDBit[k]) > 0) dist += getbits(a, bits);
                a->od[3] = a->od[2]; a->od[2] = a->od[1]; a->od[1] = a->od[0]; a->od[0] = dist;
                a->last_len = 2;
                if ((r = copy_match(a, 2, dist)) != 0) return r;
                continue;
            }
            return ARC_E_DATA;
        }
    }
    return ARC_OK;
}

/* ---------- entries ---------- */

static int dec_begin(arcrar *a, const rar_ent *e, int reset) {
    int r;
    if (!a->win) {
        uint64_t need_w, want, w = 1;
        if (a->fmt == 5) {
            want = a->max_dict; if (want < (8u << 20)) want = 8u << 20;
            need_w = a->total_unp < want ? a->total_unp : want;
        } else need_w = a->total_unp < (4u << 20) ? a->total_unp : (4u << 20);
        if (need_w < 262144) need_w = 262144;
        while (w < need_w) w <<= 1;
        if (w > a->mem_cap) return ARC_E_UNSUPPORTED;
        if (w > (uint64_t)1 << 32) return ARC_E_UNSUPPORTED;
        a->win = (uint8_t *)malloc((size_t)w);
        if (!a->win) return ARC_E_MEM;
        a->wsize = (size_t)w; a->wmask = (size_t)(w - 1);
    }
    if (!a->ib) { a->ib = (uint8_t *)malloc(IBCAP + PAD); if (!a->ib) return ARC_E_MEM; }
    if (reset) {
        a->abs = 0; memset(a->od, 0, sizeof a->od); a->last_len = 0;
        a->tables_read = 0; a->tables_read3 = 0; a->block_ppm = 0; a->esc = 2; a->ppm_ready = 0;
        memset(a->old_table, 0, sizeof a->old_table); a->prev_low = 0; a->low_rep = 0;
        a->nprg = 0; a->last_filter = 0;
    }
    a->nf = 0; a->file_end = 0; a->pov = 0;
    a->file_abs0 = a->abs; a->flushed = a->abs; a->limit_abs = a->abs + e->unp_size;
    a->oq_pos = a->oq_len = 0;
    a->pk_pos = e->pack_pos; a->pk_left = e->pack_size; a->ilen = 0; a->bitpos = 0; a->sb = 0; a->ieof = 0; a->ishort = 0;
    a->extra_dist = a->fmt == 5 && a->has_comp == 2;
    if ((r = refill(a)) != 0) return r;
    if (a->fmt == 5) {
        a->blk_end = 0; a->blk_last = 0;
        if ((r = read_block5(a)) != 0) return r;
        if ((r = read_tables5(a)) != 0) return r;
    } else if (!a->tables_read3) {
        if ((r = read_tables3(a)) != 0) return r;
    }
    return 0;
}

/* decodes a bit more and queues output; sets *fin when the entry's data is complete */
static int dec_more(arcrar *a) {
    uint64_t chunk = a->wsize / 4, target; int r;
    if (chunk > (1u << 20)) chunk = 1u << 20;
    target = a->flushed + chunk;
    if (a->nf && a->f[0].start + a->f[0].len > target && a->f[0].start <= a->flushed + a->wsize) target = a->f[0].start + a->f[0].len;
    if (target > a->limit_abs && a->limit_abs > a->flushed) target = a->limit_abs;
    r = a->fmt == 5 ? run5(a, target) : run3(a, target);
    if (r == ARC_END || a->file_end) {
        if (r == ARC_END) r = 0;
        else if (r == ARC_E_TRUNCATED && a->abs >= a->limit_abs) r = 0;
        if (r == 0) { int f = flush_out(a, 1); return f; }
    }
    if (r < 0) {
        if (a->abs >= a->limit_abs && a->fmt == 4) { a->file_end = 1; a->dec_valid = 0; (void)flush_out(a, 1); return 0; }
        return r;
    }
    return flush_out(a, a->abs >= a->limit_abs);
}

static int begin_entry(arcrar *a, uint32_t i, int reset) {
    rar_ent *e = &a->e[i];
    a->cur = (int)i; a->produced = 0; a->crc = (uint32_t)crc32(0, NULL, 0); a->done = 0;
    a->oq_pos = a->oq_len = 0;
    if (e->is_dir || e->unp_size == 0) { a->done = e->is_dir || e->stored; if (e->is_dir) return 0; }
    if (e->stored) { a->st_pos = e->pack_pos; a->st_left = e->pack_size; return 0; }
    if (e->unp_size == 0) { return 0; }
    {
        int r = dec_begin(a, e, reset);
        if (r) { a->dec_valid = 0; return r; }
        a->dec_valid = 1; a->dec_last = i;
        if (reset) a->dec_chain = i;
    }
    return 0;
}

static int read_entry(arcrar *a, uint8_t *buf, size_t cap, int64_t *out) {
    const rar_ent *e = &a->e[a->cur];
    *out = 0;
    if (a->done || e->is_dir) return 0;
    for (;;) {
        size_t n;
        if (a->oq_pos < a->oq_len) {
            n = a->oq_len - a->oq_pos; if (n > cap) n = cap;
            memcpy(buf, a->oq + a->oq_pos, n); a->oq_pos += n;
            a->crc = (uint32_t)crc32(a->crc, buf, (uInt)n);
            a->produced += n; *out = (int64_t)n;
            if (a->oq_pos == a->oq_len) a->oq_pos = a->oq_len = 0;
            return 0;
        }
        if (a->produced >= e->unp_size) {
            if (!e->stored && e->unp_size && !a->file_end && a->fmt == 4) {   /* consume the end-of-file marker: solid state depends on it */
                int r = dec_more(a);
                if (r) { a->dec_valid = 0; return r; }
                if (a->oq_pos < a->oq_len) continue;
                if (!a->file_end) continue;
            }
            a->done = 1;
            if (e->has_crc && a->crc != e->crc) { a->dec_valid = 0; return ARC_E_DATA; }
            return 0;
        }
        if (e->stored) {
            uint64_t want = e->unp_size - a->produced; int64_t got;
            if (want > cap) want = cap;
            got = a->src.read_at(a->src.ctx, a->st_pos, buf, (size_t)want);
            if (got < 0) return ARC_E_IO;
            if (got == 0) return ARC_E_TRUNCATED;
            a->st_pos += (uint64_t)got;
            a->crc = (uint32_t)crc32(a->crc, buf, (uInt)got);
            a->produced += (uint64_t)got; *out = got;
            return 0;
        }
        {
            int r;
            if (a->file_end) { a->dec_valid = 0; return ARC_E_TRUNCATED; }
            r = dec_more(a);
            if (r) { a->dec_valid = 0; return r; }
            if (a->oq_pos >= a->oq_len && a->file_end) { a->dec_valid = 0; return ARC_E_TRUNCATED; }
        }
    }
}

static int skip_entry(arcrar *a, uint32_t i, int reset) {
    int r = begin_entry(a, i, reset);
    int64_t n;
    if (r) return r;
    for (;;) {
        r = read_entry(a, a->scratch, sizeof a->scratch, &n);
        if (r) return r;
        if (n == 0) return 0;
    }
}

/* ---------- public API ---------- */

static int find_sig(arcrar *a, uint64_t *base, int *fmt) {
    static const uint8_t sig[6] = { 'R', 'a', 'r', '!', 0x1a, 0x07 };
    uint64_t scan = a->src.size < SIG_SCAN ? a->src.size : SIG_SCAN, pos = 0;
    uint8_t *buf = (uint8_t *)malloc(65536 + 16);
    int r = ARC_E_UNSUPPORTED;
    if (!buf) return ARC_E_MEM;
    while (pos + 7 <= scan + 8 && pos + 7 <= a->src.size) {
        size_t want = 65536 + 8, i; int64_t n;
        if (pos + want > a->src.size) want = (size_t)(a->src.size - pos);
        n = a->src.read_at(a->src.ctx, pos, buf, want);
        if (n < 0) { r = ARC_E_IO; break; }
        if (n < 8) break;
        for (i = 0; i + 8 <= (size_t)n; i++) {
            if (buf[i] == 'R' && memcmp(buf + i, sig, 6) == 0) {
                if (buf[i + 6] == 0) { *base = pos + i; *fmt = 4; r = 0; goto out; }
                if (buf[i + 6] == 1 && buf[i + 7] == 0) { *base = pos + i; *fmt = 5; r = 0; goto out; }
            }
        }
        if ((uint64_t)n < want || pos + 65536 >= scan) break;
        pos += 65536;
    }
out:
    free(buf);
    return r;
}

arcrar *arcrar_open(const arc_src *src, uint64_t mem_cap, int *err) {
    arcrar *a = (arcrar *)calloc(1, sizeof *a);
    uint64_t base = 0; int fmt = 0, r;
    if (!a) { if (err) *err = ARC_E_MEM; return NULL; }
    a->src = *src; a->mem_cap = mem_cap ? mem_cap : ((uint64_t)1 << 31);
    a->cur = -1; a->dec_chain = -1; a->dec_last = -1;
    arc_global_init();
    Ppmd7_Construct(&a->ppm);
    if ((r = find_sig(a, &base, &fmt)) != 0) goto fail;
    a->fmt = fmt;
    r = fmt == 5 ? parse5(a, base + 8) : parse4(a, base + 7);
    if (r != 0) goto fail;
    if (a->has_comp) {
        /* settle the decoder memory now so that unsupported archives are refused at open time */
        uint64_t w = 1, need_w;
        if (fmt == 5) { uint64_t want = a->max_dict < (8u << 20) ? (8u << 20) : a->max_dict; need_w = a->total_unp < want ? a->total_unp : want; }
        else need_w = a->total_unp < (4u << 20) ? a->total_unp : (4u << 20);
        if (need_w < 262144) need_w = 262144;
        while (w < need_w) w <<= 1;
        if (w > a->mem_cap || w > ((uint64_t)1 << 32)) { r = ARC_E_UNSUPPORTED; goto fail; }
    }
    if (err) *err = ARC_OK;
    return a;
fail:
    arcrar_close(a);
    if (err) *err = r;
    return NULL;
}

void arcrar_close(arcrar *a) {
    uint32_t i;
    if (!a) return;
    for (i = 0; i < a->n; i++) free(a->e[i].name);
    free(a->e); free(a->ib); free(a->win); free(a->oq); free(a->fb1); free(a->fb2); free(a->f); free(a->prg);
    if (a->ppm_alloc) Ppmd7_Free(&a->ppm, &g_Alloc);
    free(a);
}

uint32_t arcrar_count(const arcrar *a) { return a->n; }

size_t arcrar_name16(const arcrar *a, uint32_t i, uint16_t *buf) {
    if (i >= a->n) return 0;
    if (buf) memcpy(buf, a->e[i].name, a->e[i].name_len * sizeof(uint16_t));
    return a->e[i].name_len;
}

int arcrar_info(const arcrar *a, uint32_t i, uint64_t *size, int *is_dir) {
    if (i >= a->n) return ARC_E_ARG;
    *size = a->e[i].unp_size; *is_dir = a->e[i].is_dir;
    return ARC_OK;
}

int arcrar_select(arcrar *a, uint32_t i) {
    rar_ent *e; int comp, r;
    if (i >= a->n) return ARC_E_ARG;
    e = &a->e[i];
    comp = !e->is_dir && !e->stored && e->unp_size > 0;
    if (comp && e->solid) {
        int64_t s = (int64_t)i, from, j;
        for (; s > 0; s--) {
            const rar_ent *p = &a->e[s];
            if (!p->is_dir && !p->stored && p->unp_size > 0 && !p->solid) break;
        }
        while (s < (int64_t)i) {                       /* the first compressed entry at or after s is where the chain starts */
            const rar_ent *p = &a->e[s];
            if (!p->is_dir && !p->stored && p->unp_size > 0) break;
            s++;
        }
        if (a->dec_valid && a->dec_chain == s && a->dec_last < (int64_t)i && a->dec_last >= s) {
            if (a->cur == (int)a->dec_last && !a->done) {          /* the entry in progress must finish first */
                int64_t n;
                for (;;) { r = read_entry(a, a->scratch, sizeof a->scratch, &n); if (r) { a->dec_valid = 0; return r; } if (n == 0) break; }
            }
            from = a->dec_last + 1;
        } else { a->dec_valid = 0; from = s; }
        for (j = from; j < (int64_t)i; j++) {
            const rar_ent *p = &a->e[j];
            if (p->is_dir || p->stored || p->unp_size == 0) continue;
            if ((r = skip_entry(a, (uint32_t)j, j == s || !p->solid)) != 0) { a->dec_valid = 0; return r; }
        }
        return begin_entry(a, i, i == (uint32_t)s || !a->dec_valid);
    }
    return begin_entry(a, i, 1);
}

int64_t arcrar_read(arcrar *a, uint8_t *buf, size_t cap) {
    int64_t n = 0; int r;
    if (a->cur < 0) return ARC_E_ARG;
    if (cap == 0) return 0;
    r = read_entry(a, buf, cap, &n);
    if (r) return r;
    return n;
}
