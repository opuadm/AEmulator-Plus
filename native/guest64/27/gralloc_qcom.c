/*
 * (Since 2026-11-24 the process keeps a table of the default handles it may use - see "live" below: copies of a handle no longer free
 * the default gralloc's handle twice or use it after the free.)
 * Kernel 27 (Android 8.1): gralloc.default.so for firmware whose SurfaceFlinger is Qualcomm's (MIUI 10 of the Redmi Note 5).
 * On a Treble release the module lives on the vendor side: vendor/lib(64)/hw, the one it wraps is vendor/lib(64)/libgralloc_rom.so.
 *
 * Qualcomm's SurfaceFlinger (ExSurfaceFlinger, ExLayer) looks into the handle of every layer's buffer as into one of
 * Qualcomm's gralloc: the flags at byte 0x18 say "secure display" (then only that layer is shown), "external only"
 * (then the layer is taken off the built-in screen), the buffer type at 0x24 says "video". In a handle of the default
 * gralloc the buffer's size lies at 0x18, so what gets hidden depends on the size of the window: a 540x960 one reads
 * as "external only" and the screen stays black under a status bar.
 *
 * So the buffers stay the default gralloc's (ashmem, the plain framebuffer), and only the handle the system sees is
 * laid out as Qualcomm's, with no flags set. The firmware's own module stays next to this one as
 * libgralloc_rom.so; its handle is kept behind the one given out, and rebuilt from the fd, flags, size and
 * offset in a process the buffer comes to over binder. The tree fixer puts this file in place only where the
 * firmware's SurfaceFlinger is Qualcomm's.
 */
#include <stdint.h>

extern void *dlopen(const char *path, int flags);
extern void *dlsym(void *h, const char *name);
extern void *calloc(unsigned long n, unsigned long size);
extern void free(void *p);
extern int getpid(void);

#define EXPORT __attribute__((visibility("default")))
#ifdef __LP64__
#define REAL "/vendor/lib64/libgralloc_rom.so"
#else
#define REAL "/vendor/lib/libgralloc_rom.so"
#endif

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
struct gralloc_module {
    struct hw_module common;
    int (*registerBuffer)(const struct gralloc_module *m, const void *handle);
    int (*unregisterBuffer)(const struct gralloc_module *m, const void *handle);
    int (*lock)(const struct gralloc_module *m, const void *handle, int usage, int l, int t, int w, int h, void **vaddr);
    int (*unlock)(const struct gralloc_module *m, const void *handle);
    int (*perform)(const struct gralloc_module *m, int operation, ...);
    void *more[7];
};
struct alloc_device {
    struct hw_device common;
    int (*alloc)(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride);
    int (*free)(struct alloc_device *d, const void *handle);
    void (*dump)(struct alloc_device *d, char *buf, int len);
    void *reserved_proc[7];
};
struct wrap_device {
    struct alloc_device dev;
    struct alloc_device *real;
};
struct fb_device {
    struct hw_device common;
    uint32_t flags, width, height;
    int stride, format;
    float xdpi, ydpi, fps;
    int minSwapInterval, maxSwapInterval, numFramebuffers;
    int reserved[7];
    int (*setSwapInterval)(struct fb_device *d, int interval);
    int (*setUpdateRect)(struct fb_device *d, int l, int t, int w, int h);
    int (*post)(struct fb_device *d, const void *handle);
    int (*compositionComplete)(struct fb_device *d);
    void (*dump)(struct fb_device *d, char *buf, int len);
    int (*enableScreen)(struct fb_device *d, int enable);
    void *reserved_proc[6];
};
struct wrap_fb {
    struct fb_device dev;
    struct fb_device *real;
};

#define HARDWARE_MODULE_TAG 0x484d4f44
#define MAGIC 0x3141592      /* the default gralloc's */
#define QMAGIC MAGIC         /* not Qualcomm's 'gmsm': code that checks a handle before working on it as Qualcomm's leaves this one alone */

/* the default gralloc's handle, the same bytes for 32- and 64-bit programs */
struct rom_handle {
    int version, numFds, numInts;
    int fd, magic, flags, size, offset;
    uint64_t base;
    int pid;
};

/* the handle given out: 40 ints. Named by byte offset where Qualcomm's code reads. */
enum {
    W_VERSION = 0, W_NUMFDS, W_NUMINTS,
    W_FD,               /* 0x0c */
    W_FD_META,          /* 0x10: Qualcomm keeps the fd of its metadata here; an int here, -1 */
    W_MAGIC,            /* 0x14 */
    W_QFLAGS,           /* 0x18: Qualcomm's flags, none set */
    W_SIZE, W_OFFSET,   /* 0x1c, 0x20 */
    W_TYPE,             /* 0x24: buffer type, 0 is "UI" */
    W_FORMAT = 16, W_WIDTH, W_HEIGHT,   /* 0x40 */
    W_ROM = 28,         /* 0x70: the default gralloc's handle in this process (8 bytes) */
    W_ROM_PID = 30,     /* the process W_ROM is good in */
    W_REG_PID = 31,     /* the process that made W_ROM in registerBuffer and owes its release */
    W_FLAGS = 32,       /* the default gralloc's own flags */
    W_SELF = 34,        /* 0x88: the address of the copy of the handle that registered the buffer (8 bytes; 0 for any other copy) */
    W_INTS = 40
};

static const struct gralloc_module *real;

/*
 * The default gralloc's handle behind a handle given out is a pointer in the handle's own ints, and the handle is
 * copied freely (native_handle_clone by the HIDL mapper, by the framework's GraphicBuffer, over binder inside one
 * process...): every copy carries the pointer and the process it is good in. A copy's register and unregister used to
 * act on that pointer as if the copy were the only one - the first unregister freed the default handle, the next
 * one freed it again, and a lock through a copy used it after the free. Its 48 bytes are the size of a
 * Connection of SurfaceFlinger's EventThread, which took the freed block over and then died in ~RefBase with its
 * mRefs gone ("gralloc: invalid gralloc handle (at ...)" a few seconds before; Moto E, 8.0, from the second boot on).
 *
 * So the process keeps a table of the default handles it may use. A handle is only followed when its pointer is in
 * the table; a registered one counts the copies that registered it (a copy is told by W_SELF, its own address), and
 * it is released with the last of them; one allocated here (kind 1) is released by free() only, once.
 */
#define LIVE_MAX 8192
enum { K_ALLOC = 1, K_REG = 2 };
static struct { void *p; int kind, refs; } live[LIVE_MAX];
static volatile int live_lock;
#ifdef __aarch64__
/* by hand: the compiler's atomic exchange calls __aarch64_swp4_acq, which bionic does not have */
static int xchg_acq(volatile int *p, int v) {
    int old; unsigned fail;
    __asm__ volatile("1: ldaxr %w0, [%2]\n stxr %w1, %w3, [%2]\n cbnz %w1, 1b" : "=&r"(old), "=&r"(fail) : "r"(p), "r"(v) : "memory");
    return old;
}
#else
static int xchg_acq(volatile int *p, int v) { return __atomic_exchange_n(p, v, __ATOMIC_ACQUIRE); }
#endif
static void lk(void) { while (xchg_acq(&live_lock, 1)) { } }
static void ul(void) { __atomic_store_n(&live_lock, 0, __ATOMIC_RELEASE); }
static int live_find(const void *p) { for (int i = 0; i < LIVE_MAX; i++) if (live[i].p == p) return i; return -1; }
/* the kind of a default handle the process may use, 0 for any other pointer */
static int live_kind(const void *p) { if (!p) return 0; lk(); int i = live_find(p); int k = i < 0 ? 0 : live[i].kind; ul(); return k; }
static int live_add(void *p, int kind) {
    lk();
    int i = live_find(0);
    if (i >= 0) { live[i].p = p; live[i].kind = kind; live[i].refs = 1; }
    ul();
    return i >= 0 ? 0 : -1;
}
/* one more copy registered it: 1 if the pointer is a registered one of the table */
static int live_ref(void *p) {
    lk();
    int i = live_find(p), ok = i >= 0 && live[i].kind == K_REG;
    if (ok) live[i].refs++;
    ul();
    return ok;
}
/* a copy unregisters: the copies left, or -1 when the pointer is not a registered one of the table */
static int live_unref(void *p) {
    lk();
    int i = live_find(p), left = -1;
    if (i >= 0 && live[i].kind == K_REG) { left = --live[i].refs; if (left <= 0) { live[i].p = 0; left = 0; } }
    ul();
    return left;
}
/* take an allocated one out of the table: 1 if it was there (and so is freed here, once) */
static int live_drop(void *p) {
    lk();
    int i = live_find(p), ok = i >= 0 && live[i].kind == K_ALLOC;
    if (ok) live[i].p = 0;
    ul();
    return ok;
}

static int mine(const int *w) { return w && w[W_VERSION] == 12 && w[W_NUMFDS] + w[W_NUMINTS] == W_INTS - 3 && w[W_MAGIC] == QMAGIC; }
static struct rom_handle *rom_raw(const int *w) { return (struct rom_handle *)(uintptr_t)*(const uint64_t *)&w[W_ROM]; }
static void set_rom(int *w, struct rom_handle *r) { *(uint64_t *)&w[W_ROM] = (uintptr_t)r; }
static uint64_t self_of(const int *w) { return *(const uint64_t *)&w[W_SELF]; }
static void set_self(int *w, uint64_t v) { *(uint64_t *)&w[W_SELF] = v; }
/* the default handle a copy may be used with here: the one its pointer names, if this process still has it */
static struct rom_handle *rom_of(const int *w) {
    struct rom_handle *r = rom_raw(w);
    return r && w[W_ROM_PID] == getpid() && live_kind(r) ? r : 0;
}

static int w_register(const struct gralloc_module *m, const void *h) {
    (void)m;
    int *w = (int *)h;
    if (!real || !mine(w)) return -22;
    struct rom_handle *r = rom_of(w);
    if (r) {
        if (live_kind(r) == K_ALLOC) return 0;              /* allocated here: nothing to register */
        if (self_of(w) == (uintptr_t)w) return 0;           /* this copy registered it already */
        if (live_ref(r)) { set_self(w, (uintptr_t)w); w[W_REG_PID] = getpid(); return 0; }   /* another copy did */
    }
    r = calloc(1, sizeof *r);
    if (!r) return -12;
    r->version = 12; r->numFds = 1; r->numInts = 8;
    r->fd = w[W_FD]; r->magic = MAGIC; r->flags = w[W_FLAGS]; r->size = w[W_SIZE]; r->offset = w[W_OFFSET];
    int e = real->registerBuffer(real, r);
    if (e) { free(r); return e; }
    if (live_add(r, K_REG)) { real->unregisterBuffer(real, r); free(r); return -12; }
    set_rom(w, r);
    w[W_ROM_PID] = w[W_REG_PID] = getpid();
    set_self(w, (uintptr_t)w);
    return 0;
}
static int w_unregister(const struct gralloc_module *m, const void *h) {
    (void)m;
    int *w = (int *)h;
    if (!real || !mine(w)) return -22;
    struct rom_handle *r = rom_of(w);
    if (!r || w[W_REG_PID] != getpid() || self_of(w) != (uintptr_t)w) return 0;   /* not registered through this copy */
    set_rom(w, 0);
    w[W_ROM_PID] = w[W_REG_PID] = 0;
    set_self(w, 0);
    if (live_unref(r) != 0) return 0;                       /* other copies still have it registered (or it is gone already) */
    int e = real->unregisterBuffer(real, r);
    free(r);
    return e;
}
static int w_lock(const struct gralloc_module *m, const void *hnd, int usage, int l, int t, int w, int h, void **vaddr) {
    (void)m;
    struct rom_handle *r = mine(hnd) ? rom_of(hnd) : 0;
    if (!real || !r) return -22;
    return real->lock(real, r, usage, l, t, w, h, vaddr);
}
static int w_unlock(const struct gralloc_module *m, const void *h) {
    (void)m;
    struct rom_handle *r = mine(h) ? rom_of(h) : 0;
    if (!real || !r) return -22;
    return real->unlock(real, r);
}

static int d_alloc(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride) {
    struct alloc_device *r = ((struct wrap_device *)d)->real;
    const void *rh = 0;
    int e = r->alloc(r, w, h, format, usage, &rh, stride);
    if (e) return e;
    const struct rom_handle *q = rh;
    int *o = calloc(W_INTS, sizeof(int));
    if (!o || live_add((void *)q, K_ALLOC)) { if (o) free(o); r->free(r, rh); return -12; }
    o[W_VERSION] = 12; o[W_NUMFDS] = 1; o[W_NUMINTS] = W_INTS - 4;
    o[W_FD] = q->fd; o[W_FD_META] = -1;
    o[W_MAGIC] = QMAGIC; o[W_FLAGS] = q->flags; o[W_SIZE] = q->size; o[W_OFFSET] = q->offset;
    o[W_FORMAT] = format; o[W_WIDTH] = stride ? *stride : w; o[W_HEIGHT] = h;
    set_rom(o, (struct rom_handle *)q);
    o[W_ROM_PID] = getpid();
    *handle = o;
    return 0;
}
static int d_free(struct alloc_device *d, const void *handle) {
    struct alloc_device *r = ((struct wrap_device *)d)->real;
    int *w = (int *)handle;
    if (!mine(w)) return -22;
    struct rom_handle *q = rom_raw(w);
    int e = q && w[W_ROM_PID] == getpid() && !w[W_REG_PID] && live_drop(q) ? r->free(r, q) : 0;   /* once, and only what was allocated here */
    free(w);
    return e;
}
static int d_close(struct hw_device *d) {
    struct alloc_device *r = ((struct wrap_device *)d)->real;
    int e = r->common.close ? r->common.close(&r->common) : 0;
    free(d);
    return e;
}

/* the framebuffer device: the firmware's, which takes the buffer to show by the default gralloc's handle */
#define FB(d) (((struct wrap_fb *)(d))->real)
static int f_swap_interval(struct fb_device *d, int interval) { return FB(d)->setSwapInterval(FB(d), interval); }
static int f_update_rect(struct fb_device *d, int l, int t, int w, int h) { return FB(d)->setUpdateRect(FB(d), l, t, w, h); }
static int f_post(struct fb_device *d, const void *handle) {
    if (!mine(handle) || !rom_of(handle)) return -22;
    return FB(d)->post(FB(d), rom_of(handle));
}
static int f_composition_complete(struct fb_device *d) { return FB(d)->compositionComplete(FB(d)); }
static int f_enable_screen(struct fb_device *d, int enable) { return FB(d)->enableScreen(FB(d), enable); }
static int f_close(struct hw_device *d) {
    struct fb_device *r = FB(d);
    int e = r->common.close ? r->common.close(&r->common) : 0;
    free(d);
    return e;
}
static struct hw_device *wrap_fb(const struct hw_module *m, struct fb_device *r) {
    struct wrap_fb *f = calloc(1, sizeof *f);
    if (!f) return 0;
    f->dev = *r;
    f->real = r;
    f->dev.common.module = (struct hw_module *)m;
    f->dev.common.close = f_close;
    if (r->setSwapInterval) f->dev.setSwapInterval = f_swap_interval;
    if (r->setUpdateRect) f->dev.setUpdateRect = f_update_rect;
    f->dev.post = f_post;
    if (r->compositionComplete) f->dev.compositionComplete = f_composition_complete;
    f->dev.dump = 0;
    if (r->enableScreen) f->dev.enableScreen = f_enable_screen;
    return &f->dev.common;
}

static int w_open(const struct hw_module *m, const char *id, struct hw_device **out) {
    if (!real) return -22;
    struct hw_device *dev = 0;
    int e = real->common.methods->open(&real->common, id, &dev);
    if (e || !dev) return e ? e : -22;
    /* "gpu0" is the allocator, "fb0" the framebuffer */
    if (id[0] == 'f' && id[1] == 'b' && id[2] == '0' && !id[3]) {
        *out = wrap_fb(m, (struct fb_device *)dev);
        if (!*out) { if (dev->close) dev->close(dev); return -12; }
        return 0;
    }
    if (!(id[0] == 'g' && id[1] == 'p' && id[2] == 'u' && id[3] == '0' && !id[4])) { *out = dev; return 0; }
    struct wrap_device *wd = calloc(1, sizeof *wd);
    if (!wd) { if (dev->close) dev->close(dev); return -12; }
    wd->dev = *(struct alloc_device *)dev;
    wd->real = (struct alloc_device *)dev;
    wd->dev.common.module = (struct hw_module *)m;
    wd->dev.common.close = d_close;
    wd->dev.alloc = d_alloc;
    wd->dev.free = d_free;
    wd->dev.dump = 0;
    *out = &wd->dev.common;
    return 0;
}

static struct hw_module_methods methods = { w_open };

EXPORT struct gralloc_module HMI = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = 1,
        .hal_api_version = 0,
        .id = "gralloc",
        .name = "AEmulator gralloc (default gralloc, Qualcomm handle layout)",
        .author = "AEmulator",
        .methods = &methods,
    },
    .registerBuffer = w_register,
    .unregisterBuffer = w_unregister,
    .lock = w_lock,
    .unlock = w_unlock,
};

__attribute__((constructor)) static void init(void) {
    void *h = dlopen(REAL, 2 /* RTLD_NOW */);
    real = h ? dlsym(h, "HMI") : 0;
    if (real) {
        HMI.common.module_api_version = real->common.module_api_version;
        HMI.common.hal_api_version = real->common.hal_api_version;
    }
}
