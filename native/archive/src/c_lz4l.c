/*
 * LZ4 legacy format (`lz4 -l`, Linux kernel / initramfs / old Android ramdisks), decoder only.
 *
 *   stream := magic(0x184C2102, LE32) { block }
 *   block  := compressed_size(LE32) compressed_size bytes of an LZ4 block (independent of every other block)
 *
 * Every block decodes to at most 8 MiB, so a size above LZ4_COMPRESSBOUND(8 MiB) cannot be a block: that is how a
 * following stream (another legacy magic, or a skippable frame) shows up. Like the `lz4` tool this decoder takes
 * concatenated legacy streams and skips skippable frames (0x184D2A50..5F). Besides that:
 *   - end of input exactly at a block boundary ends the stream; end of input inside a size field or a block is
 *     ARC_E_TRUNCATED;
 *   - a block size of 0 never comes out of an encoder: it is the zero padding that firmware images put behind
 *     the stream (page-aligned ramdisks), and ends the decode, the padding is left unread; the same goes for 1..3
 *     zero bytes at the very end of the input (a padded stream does not have to end on a 4-byte boundary);
 *   - the regular LZ4 frame magic (0x184D2204) is not this format: ARC_E_DATA (use ARC_LZ4F).
 */
#include <stdlib.h>
#include <string.h>
#include "lz4.h"
#include "arc_internal.h"

#define LEG_MAGIC      0x184C2102u
#define LEG_BLOCK_MAX  (8u << 20)                                  /* decoded size of one block */
#define LEG_CBOUND     (LEG_BLOCK_MAX + LEG_BLOCK_MAX / 255 + 16)  /* LZ4_COMPRESSBOUND(8 MiB) = 8421504 */

enum { S_MAGIC, S_SIZE, S_SKIPLEN, S_SKIP, S_BLOCK_IN, S_BLOCK_OUT };

typedef struct {
    int state;
    int started;            /* a magic was seen: input may end at a block boundary */
    uint8_t hdr[4];         /* partial 32-bit field */
    unsigned hdr_n;
    uint32_t want;          /* S_SKIP: bytes left to skip, S_BLOCK_IN: compressed size of the block */
    uint8_t *cbuf;          /* compressed block (LEG_CBOUND) */
    uint32_t cgot;
    uint8_t *dbuf;          /* decoded block (LEG_BLOCK_MAX), allocated when the first block arrives */
    uint32_t dlen, dpos;
} lgst;

static void *lg_create(int codec) {
    (void)codec;
    return calloc(1, sizeof(lgst));    /* the two big buffers are allocated on first use: an empty stream costs nothing */
}

static void lg_destroy(void *p) {
    lgst *s = (lgst *)p;
    if (!s) return;
    free(s->cbuf);
    free(s->dbuf);
    free(s);
}

/* Collects a little-endian 32-bit field across calls. Returns 1 when [*v] is complete. */
static int lg_u32(lgst *s, const uint8_t *in, size_t in_len, size_t *used, uint32_t *v) {
    while (s->hdr_n < 4 && *used < in_len) s->hdr[s->hdr_n++] = in[(*used)++];
    if (s->hdr_n < 4) return 0;
    *v = (uint32_t)s->hdr[0] | (uint32_t)s->hdr[1] << 8 | (uint32_t)s->hdr[2] << 16 | (uint32_t)s->hdr[3] << 24;
    s->hdr_n = 0;
    return 1;
}

static int lg_step(void *p, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    lgst *s = (lgst *)p;
    *used = 0; *made = 0;
    for (;;) {
        switch (s->state) {
        case S_MAGIC: {
            uint32_t m;
            if (s->hdr_n == 0 && *used == in_len) return eof && s->started ? ARC_END : (eof ? ARC_E_TRUNCATED : ARC_OK);
            if (!lg_u32(s, in, in_len, used, &m)) return eof ? ARC_E_TRUNCATED : ARC_OK;
            if (m == LEG_MAGIC) { s->started = 1; s->state = S_SIZE; }
            else if ((m & 0xFFFFFFF0u) == 0x184D2A50u) { s->started = 1; s->state = S_SKIPLEN; }
            else return ARC_E_DATA;
            break;
        }
        case S_SIZE: {
            uint32_t n;
            if (s->hdr_n == 0 && *used == in_len) return eof ? ARC_END : ARC_OK;   /* clean end at a block boundary */
            if (!lg_u32(s, in, in_len, used, &n)) {
                unsigned i;
                if (!eof) return ARC_OK;
                for (i = 0; i < s->hdr_n; i++) if (s->hdr[i]) return ARC_E_TRUNCATED;
                return ARC_END;                                                   /* 1..3 bytes of zero padding */
            }
            if (n == 0) return ARC_END;                                           /* zero padding behind the stream */
            if (n == LEG_MAGIC) { s->started = 1; break; }                        /* concatenated stream: stay in S_SIZE */
            if ((n & 0xFFFFFFF0u) == 0x184D2A50u) { s->state = S_SKIPLEN; break; }
            if (n > LEG_CBOUND) return ARC_E_DATA;                                /* not a block, not a known frame */
            if (!s->cbuf) {
                s->cbuf = (uint8_t *)malloc(LEG_CBOUND);
                s->dbuf = (uint8_t *)malloc(LEG_BLOCK_MAX);
                if (!s->cbuf || !s->dbuf) return ARC_E_MEM;
            }
            s->want = n; s->cgot = 0; s->state = S_BLOCK_IN;
            break;
        }
        case S_SKIPLEN: {
            uint32_t n;
            if (!lg_u32(s, in, in_len, used, &n)) return eof ? ARC_E_TRUNCATED : ARC_OK;
            s->want = n; s->state = n ? S_SKIP : S_MAGIC;
            break;
        }
        case S_SKIP: {
            size_t k = in_len - *used;
            if (k > s->want) k = s->want;
            *used += k; s->want -= (uint32_t)k;
            if (s->want == 0) { s->state = S_MAGIC; break; }
            return eof ? ARC_E_TRUNCATED : ARC_OK;
        }
        case S_BLOCK_IN: {
            size_t k = in_len - *used;
            if (k > s->want - s->cgot) k = s->want - s->cgot;
            if (k) { memcpy(s->cbuf + s->cgot, in + *used, k); *used += k; s->cgot += (uint32_t)k; }
            if (s->cgot < s->want) return eof ? ARC_E_TRUNCATED : ARC_OK;
            {
                int r = LZ4_decompress_safe((const char *)s->cbuf, (char *)s->dbuf, (int)s->want, (int)LEG_BLOCK_MAX);
                if (r < 0) return ARC_E_DATA;
                s->dlen = (uint32_t)r; s->dpos = 0;
                s->state = S_BLOCK_OUT;
            }
            break;
        }
        case S_BLOCK_OUT: {
            size_t k = s->dlen - s->dpos;
            if (k > cap - *made) k = cap - *made;
            if (k) { memcpy(out + *made, s->dbuf + s->dpos, k); *made += k; s->dpos += (uint32_t)k; }
            if (s->dpos < s->dlen) return ARC_OK;                                 /* output full */
            s->state = S_SIZE;
            break;
        }
        }
    }
}

const arc_ops arc_ops_lz4l = { lg_create, lg_step, lg_destroy };
