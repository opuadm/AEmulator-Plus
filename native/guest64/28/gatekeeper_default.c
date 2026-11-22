/*
 * Kernel 28 (Android 9): gatekeeper.default.so, a software "gatekeeper" hardware module (hardware/gatekeeper.h API).
 * Added for AEmulator Plus (2026-11-21).
 *
 * Why: the engine's android.hardware.gatekeeper@1.0-impl.so (the HIDL service of vendor.gatekeeper-1-0) opens the hardware
 * module "gatekeeper" through hw_get_module_by_class(). A Samsung Exynos firmware only has its TEE-backed
 * gatekeeper.exynos8890.so (the tree fixer sets it aside: it needs the secure world), so nothing answers, both the HIDL
 * service and gatekeeperd log "Unable to open GateKeeper HAL" and exit, LockSettingsService gets no IGateKeeperService and
 * system_server dies with a NullPointerException in reportDeviceSetupComplete() (zygote follows: exit code 137).
 *
 * What: enroll / verify of a password handle in plain software, no secure world.
 *   handle (58 bytes, the layout of gatekeeper's password_handle_t): version(1)=0, secure user id(8), flags(8), salt(8),
 *     signature(32), hardware_backed(1)=0
 *   signature = PBKDF2-HMAC-SHA256(password, devkey || version..salt, 2048 rounds): AOSP's soft gatekeeper uses scrypt, which is
 *     not worth carrying here; the key of the device (32 random bytes, /data/misc/gatekeeper/aemu.key) keeps handles of one
 *     image from being checked on another.
 *   auth token: hw_auth_token_t (69 bytes), HMAC-SHA256 with the same key. Nothing here checks it against a keymaster key.
 * Not hardware backed, no throttling, delete_user / delete_all_users are not implemented (-ENOSYS, as AOSP's soft one).
 *
 * Freestanding like gralloc_exynos.c: only libc's calloc/open/read/write/close/clock_gettime are imported.
 */
#include <stdint.h>

#define EXPORT __attribute__((visibility("default")))
typedef __SIZE_TYPE__ usize;

extern void *calloc(usize n, usize size);
extern int open(const char *path, int flags, ...);
extern long read(int fd, void *buf, usize n);
extern long write(int fd, const void *buf, usize n);
extern int close(int fd);
extern int clock_gettime(int clk, void *ts);

static void mcpy(void *d, const void *s, usize n) { uint8_t *a = d; const uint8_t *b = s; while (n--) *a++ = *b++; }
static void mset(void *d, int c, usize n) { uint8_t *a = d; while (n--) *a++ = (uint8_t)c; }

/* ---- SHA-256 / HMAC / PBKDF2 ------------------------------------------------------------------------------------ */
typedef struct { uint32_t h[8]; uint8_t buf[64]; uint32_t n; uint32_t lenLo, lenHi; } sha_t;
static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2 };
#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha_block(sha_t *s, const uint8_t *p) {
    uint32_t w[64], a, b, c, d, e, f, g, h, t1, t2;
    for (int i = 0; i < 16; i++) w[i] = (uint32_t)p[4*i] << 24 | (uint32_t)p[4*i+1] << 16 | (uint32_t)p[4*i+2] << 8 | p[4*i+3];
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i-15], 7) ^ ROR(w[i-15], 18) ^ (w[i-15] >> 3);
        uint32_t s1 = ROR(w[i-2], 17) ^ ROR(w[i-2], 19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    a = s->h[0]; b = s->h[1]; c = s->h[2]; d = s->h[3]; e = s->h[4]; f = s->h[5]; g = s->h[6]; h = s->h[7];
    for (int i = 0; i < 64; i++) {
        t1 = h + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i];
        t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    s->h[0] += a; s->h[1] += b; s->h[2] += c; s->h[3] += d; s->h[4] += e; s->h[5] += f; s->h[6] += g; s->h[7] += h;
}
static void sha_init(sha_t *s) {
    static const uint32_t H0[8] = { 0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19 };
    mcpy(s->h, H0, sizeof H0); s->n = 0; s->lenLo = s->lenHi = 0;
}
static void sha_update(sha_t *s, const uint8_t *p, usize n) {
    uint32_t add = (uint32_t)n << 3;
    s->lenHi += (uint32_t)((uint64_t)n >> 29);
    if ((s->lenLo += add) < add) s->lenHi++;
    while (n--) { s->buf[s->n++] = *p++; if (s->n == 64) { sha_block(s, s->buf); s->n = 0; } }
}
static void sha_final(sha_t *s, uint8_t out[32]) {
    uint32_t hi = s->lenHi, lo = s->lenLo;
    uint8_t pad = 0x80;
    sha_update(s, &pad, 1);
    pad = 0;
    while (s->n != 56) sha_update(s, &pad, 1);
    uint8_t l[8] = { hi >> 24, hi >> 16, hi >> 8, hi, lo >> 24, lo >> 16, lo >> 8, lo };
    for (int i = 0; i < 8; i++) s->buf[s->n++] = l[i];
    sha_block(s, s->buf);
    for (int i = 0; i < 8; i++) { out[4*i] = s->h[i] >> 24; out[4*i+1] = s->h[i] >> 16; out[4*i+2] = s->h[i] >> 8; out[4*i+3] = s->h[i]; }
}

typedef struct { sha_t in, out; } hmac_t;
static void hmac_init(hmac_t *m, const uint8_t *key, usize klen) {
    uint8_t k[64], ip[64], op[64];
    mset(k, 0, 64);
    if (klen > 64) { sha_t t; sha_init(&t); sha_update(&t, key, klen); sha_final(&t, k); } else mcpy(k, key, klen);
    for (int i = 0; i < 64; i++) { ip[i] = k[i] ^ 0x36; op[i] = k[i] ^ 0x5c; }
    sha_init(&m->in); sha_update(&m->in, ip, 64);
    sha_init(&m->out); sha_update(&m->out, op, 64);
}
static void hmac_final(hmac_t *m, uint8_t out[32]) {
    uint8_t d[32];
    sha_final(&m->in, d);
    sha_update(&m->out, d, 32);
    sha_final(&m->out, out);
}
static void hmac(const uint8_t *key, usize klen, const uint8_t *msg, usize n, uint8_t out[32]) {
    hmac_t m; hmac_init(&m, key, klen); sha_update(&m.in, msg, n); hmac_final(&m, out);
}
/* PBKDF2-HMAC-SHA256, one 32-byte block */
static void pbkdf2(const uint8_t *pw, usize pwlen, const uint8_t *salt, usize slen, uint32_t rounds, uint8_t out[32]) {
    hmac_t base, m;
    uint8_t u[32];
    static const uint8_t one[4] = { 0, 0, 0, 1 };
    hmac_init(&base, pw, pwlen);
    m = base; sha_update(&m.in, salt, slen); sha_update(&m.in, one, 4); hmac_final(&m, u);
    mcpy(out, u, 32);
    for (uint32_t r = 1; r < rounds; r++) {
        m = base; sha_update(&m.in, u, 32); hmac_final(&m, u);
        for (int i = 0; i < 32; i++) out[i] ^= u[i];
    }
}

/* ---- key of the device ----------------------------------------------------------------------------------------- */
#define KEY_FILE "/data/misc/gatekeeper/aemu.key"
static uint8_t devkey[32];
static int have_key;

static void load_key(void) {
    if (have_key) return;
    int fd = open(KEY_FILE, 0 /* O_RDONLY */);
    if (fd >= 0) { long n = read(fd, devkey, 32); close(fd); if (n == 32) { have_key = 1; return; } }
    int ok = 0;
    fd = open("/dev/urandom", 0);
    if (fd >= 0) { ok = read(fd, devkey, 32) == 32; close(fd); }
    if (!ok) {                       /* no entropy source: a fixed key still lets enrol / verify agree inside this image */
        for (int i = 0; i < 32; i++) devkey[i] = (uint8_t)(0xa5 ^ (i * 37));
    } else {
        fd = open(KEY_FILE, 0x1 | 0x40 | 0x80 /* O_WRONLY|O_CREAT|O_EXCL */, 0600);
        if (fd >= 0) { (void)write(fd, devkey, 32); close(fd); }
    }
    have_key = 1;
}
static void random_bytes(uint8_t *p, usize n) {
    int fd = open("/dev/urandom", 0);
    long got = fd >= 0 ? read(fd, p, n) : -1;
    if (fd >= 0) close(fd);
    if (got != (long)n) {            /* fall back on the key, the clock and the address: never the same twice, not secret */
        long ts[2] = { 0, 0 }; clock_gettime(7, ts);
        uint8_t seed[32 + 16]; mcpy(seed, devkey, 32); mcpy(seed + 32, ts, sizeof ts);
        uint8_t d[32]; hmac(seed, sizeof seed, (const uint8_t *)&p, sizeof p, d);
        for (usize i = 0; i < n; i++) p[i] = d[i & 31];
    }
}

/* ---- password handle and auth token ---------------------------------------------------------------------------- */
struct __attribute__((packed)) pw_handle { uint8_t version; uint64_t user_id; uint64_t flags; uint64_t salt; uint8_t sig[32]; uint8_t hw_backed; };
struct __attribute__((packed)) auth_token { uint8_t version; uint64_t challenge; uint64_t user_id; uint64_t authenticator_id; uint32_t type_be; uint64_t ts_be; uint8_t hmac[32]; };
#define HANDLE_LEN 58
#define TOKEN_LEN 69

static void sign(const struct pw_handle *h, const uint8_t *pw, uint32_t pwlen, uint8_t out[32]) {
    uint8_t salt[32 + 25];
    mcpy(salt, devkey, 32);
    mcpy(salt + 32, h, 25);          /* version .. salt */
    pbkdf2(pw, pwlen, salt, sizeof salt, 2048, out);
}
static int same(const uint8_t *a, const uint8_t *b, usize n) { uint8_t d = 0; while (n--) d |= *a++ ^ *b++; return d == 0; }

/* ---- hardware/gatekeeper.h ------------------------------------------------------------------------------------- */
struct hw_module;
struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module {
    uint32_t tag;
    uint16_t module_api_version, hal_api_version;
    const char *id, *name, *author;
    struct hw_module_methods *methods;
    void *dso;
#ifdef __LP64__
    uint64_t reserved[32 - 7];
#else
    uint32_t reserved[32 - 7];
#endif
};
struct hw_device {
    uint32_t tag, version;
    struct hw_module *module;
#ifdef __LP64__
    uint64_t reserved[12];
#else
    uint32_t reserved[12];
#endif
    int (*close)(struct hw_device *dev);
};
struct gk_device {
    struct hw_device common;
    int (*enroll)(const struct gk_device *d, uint32_t uid, const uint8_t *cur_handle, uint32_t cur_handle_len,
                  const uint8_t *cur_pw, uint32_t cur_pw_len, const uint8_t *new_pw, uint32_t new_pw_len,
                  uint8_t **out_handle, uint32_t *out_handle_len);
    int (*verify)(const struct gk_device *d, uint32_t uid, uint64_t challenge, const uint8_t *handle, uint32_t handle_len,
                  const uint8_t *pw, uint32_t pw_len, uint8_t **auth_token, uint32_t *auth_token_len, uint8_t *reenroll);
    int (*delete_user)(const struct gk_device *d, uint32_t uid);
    int (*delete_all_users)(const struct gk_device *d);
};

#define E_INVAL (-22)
#define E_NOMEM (-12)
#define E_NOSYS (-38)

static int check_handle(const uint8_t *hd, uint32_t len, const uint8_t *pw, uint32_t pwlen) {
    if (!hd || len != HANDLE_LEN || hd[0] != 0 || (!pw && pwlen)) return 0;
    const struct pw_handle *h = (const struct pw_handle *)hd;
    uint8_t sig[32];
    sign(h, pw, pwlen, sig);
    return same(sig, h->sig, 32);
}

static int gk_enroll(const struct gk_device *d, uint32_t uid, const uint8_t *cur_handle, uint32_t cur_handle_len,
                     const uint8_t *cur_pw, uint32_t cur_pw_len, const uint8_t *new_pw, uint32_t new_pw_len,
                     uint8_t **out_handle, uint32_t *out_len) {
    (void)d; (void)uid;
    if (!out_handle || !out_len || (!new_pw && new_pw_len)) return E_INVAL;
    uint64_t sid;
    if (cur_handle && cur_handle_len) {              /* a password change: the old one has to be right, the secure id stays */
        if (!check_handle(cur_handle, cur_handle_len, cur_pw, cur_pw_len)) return E_INVAL;
        mcpy(&sid, cur_handle + 1, 8);
    } else {
        random_bytes((uint8_t *)&sid, 8);
        if (!sid) sid = 1;
    }
    struct pw_handle *h = calloc(1, sizeof *h);
    if (!h) return E_NOMEM;
    h->version = 0; h->user_id = sid; h->flags = 0; h->hw_backed = 0;
    random_bytes((uint8_t *)&h->salt, 8);
    sign(h, new_pw, new_pw_len, h->sig);
    *out_handle = (uint8_t *)h;
    *out_len = HANDLE_LEN;
    return 0;
}

static int gk_verify(const struct gk_device *d, uint32_t uid, uint64_t challenge, const uint8_t *handle, uint32_t handle_len,
                     const uint8_t *pw, uint32_t pw_len, uint8_t **out_token, uint32_t *out_token_len, uint8_t *reenroll) {
    (void)d; (void)uid;
    if (!check_handle(handle, handle_len, pw, pw_len)) return E_INVAL;
    if (reenroll) *reenroll = 0;
    if (out_token && out_token_len) {
        struct auth_token *t = calloc(1, sizeof *t);
        if (!t) return E_NOMEM;
        uint64_t sid; mcpy(&sid, handle + 1, 8);
        long ts[2] = { 0, 0 };
        clock_gettime(7 /* CLOCK_BOOTTIME */, ts);
        uint64_t ms = (uint64_t)(uint32_t)ts[0] * 1000u + ((uint32_t)ts[1] >> 20);   /* (nanoseconds >> 20) is a millisecond within 5% */
        t->version = 0; t->challenge = challenge; t->user_id = sid; t->authenticator_id = sid;
        t->type_be = 0x01000000u;                                                    /* HW_AUTH_PASSWORD, big endian */
        for (int i = 0; i < 8; i++) ((uint8_t *)&t->ts_be)[i] = (uint8_t)(ms >> (56 - 8 * i));
        hmac(devkey, 32, (const uint8_t *)t, TOKEN_LEN - 32, t->hmac);
        *out_token = (uint8_t *)t;
        *out_token_len = TOKEN_LEN;
    }
    return 0;
}
static int gk_delete_user(const struct gk_device *d, uint32_t uid) { (void)d; (void)uid; return E_NOSYS; }
static int gk_delete_all(const struct gk_device *d) { (void)d; return E_NOSYS; }
static int gk_close(struct hw_device *d) { extern void free(void *); free(d); return 0; }

static int gk_open(const struct hw_module *m, const char *id, struct hw_device **out) {
    (void)id;
    struct gk_device *d = calloc(1, sizeof *d);
    if (!d) return E_NOMEM;
    load_key();
    d->common.tag = 0x48574454;                      /* HARDWARE_DEVICE_TAG */
    d->common.version = 1;
    d->common.module = (struct hw_module *)m;
    d->common.close = gk_close;
    d->enroll = gk_enroll; d->verify = gk_verify; d->delete_user = gk_delete_user; d->delete_all_users = gk_delete_all;
    *out = &d->common;
    return 0;
}
static struct hw_module_methods methods = { gk_open };

EXPORT struct hw_module HMI = {
    .tag = 0x48574d54,                               /* HARDWARE_MODULE_TAG */
    .module_api_version = 1, .hal_api_version = 0,
    .id = "gatekeeper", .name = "AEmulator software gatekeeper", .author = "AEmulator Plus",
    .methods = &methods,
};
