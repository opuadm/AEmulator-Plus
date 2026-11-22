// Guest shim for x86-32 guests (LD_PRELOAD, bionic firmware): host-path hiding for zygote's fd-table check.
//
// Android 7.0+ zygote walks /proc/self/fd before every fork and aborts the whole system ("Not whitelisted : /dhd.owners",
// "Unable to construct file descriptor table", exit 134) when an open descriptor does not lead to a whitelisted guest path.
// The translator keeps its ownership table (/dhd.owners) open in every guest process, the stdio of guest services are
// pipes or the host's log file, and bound unix sockets carry the phone's real image path. The ARM build of libaemushim.so
// (aemushim.c, guest_fd_name / getsockname) answers readlink()/readlinkat()/getsockname() so that the guest sees only
// its own paths; this is the same logic for x86-32, with raw int 0x80 system calls and no libc beyond bionic's __errno.
//
// The same file carries the other hooks an Android 7.x userland needs on the phone's kernel (what each one fixes is
// noted at the function): extended attributes (installd), seccomp (mediaextractor/mediacodec), SO_MARK (netd), alarm
// clocks (system_server), the emulated touchpad and keyboard of the input method \"mouse and keyboard\" (vinput.h,
// open() + ioctl() of the two emulator-owned nodes in /dev/input), and the virtual console of Android-x86 builds that are
// not \"user\" (/dev/tty0, VT_GETSTATE: InputFlinger's EventHub drops every event when the call fails). The rest of aemushim.c (power, mounts, execve of
// scripts, netmgr, ...) is not ported.
//
// build: native/guestshim/build-x86.sh  ->  app/src/main/assets/engines/common-x86/libaemushim.so

#define EXPORT __attribute__((visibility("default")))

#define NR_open          5
#define NR_read          3
#define NR_write         4
#define NR_close         6
#define NR_lseek        19
#define NR_sysinfo     116
#define NR_memfd_create 356
#define NR_ioctl        54
#define NR_openat      295
#define NR_readlink     85
#define NR_readlinkat  305
#define NR_socketcall  102
#define SC_GETSOCKNAME   6
#define SC_SETSOCKOPT   14
#define NR_prctl       172
#define NR_timerfd_create 322
#define NR_setxattr    226
#define NR_lsetxattr   227
#define NR_fsetxattr   228
#define NR_getxattr    229
#define NR_lgetxattr   230
#define NR_fgetxattr   231
#define ENODATA         61
#define ERANGE          34
#define ENOSYS          38
#define EOPNOTSUPP      95

extern int *__errno(void);

// ebx is the GOT register under -fPIC, so the first argument travels in edi and is swapped into ebx only around the call
static inline long sys4(long nr, long a, long b, long c, long d)
{
    long r;
    __asm__ volatile("xchgl %%edi, %%ebx\n\tint $0x80\n\txchgl %%edi, %%ebx"
                     : "=a"(r), "+D"(a)
                     : "0"(nr), "c"(b), "d"(c), "S"(d)
                     : "memory");
    return r;
}

static inline long sys3(long nr, long a, long b, long c) { return sys4(nr, a, b, c, 0); }

// five arguments: ebx is saved and loaded by hand (the operand for it is any other register)
static inline long sys5(long nr, long a, long b, long c, long d, long e)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\tmovl %2, %%ebx\n\tint $0x80\n\tpopl %%ebx"
                     : "=a"(r)
                     : "0"(nr), "r"(a), "c"(b), "d"(c), "S"(d), "D"(e)
                     : "memory");
    return r;
}

static int fail(long r) { *__errno() = (int)-r; return -1; }

static int starts_at(const char *s, int n, int at, const char *m, int mlen)
{
    if (at + mlen > n) return 0;
    for (int k = 0; k < mlen; k++) if (s[at + k] != m[k]) return 0;
    return 1;
}

// ".../files/images/<id>/root/<rest>" -> "/<rest>"
static int strip_root(char *s, int n)
{
    static const char m[] = "/files/images/";
    for (int i = 0; i + 14 < n; i++) {
        if (!starts_at(s, n, i, m, 14)) continue;
        int j = i + 14;
        while (j < n && s[j] != '/') j++;
        if (j + 5 > n || s[j + 1] != 'r' || s[j + 2] != 'o' || s[j + 3] != 'o' || s[j + 4] != 't') return n;
        int from = j + 5, len = n - from;
        if (len <= 0) { s[0] = '/'; return 1; }
        for (int q = 0; q < len; q++) s[q] = s[from + q];
        return len;
    }
    return n;
}

static int whole(const char *s, int n, const char *m)
{
    int k = 0;
    while (k < n && m[k] && s[k] == m[k]) k++;
    return k == n && !m[k];
}

static long guest_fd_name_(char *buf, long n, unsigned long size)
{
    n = strip_root(buf, (int)n);
    // stdio of guest processes: pipes to the host's log readers, or the log file itself
    int logfile = 0;
    {
        static const char m[] = "/files/images/";
        for (int i = 0; i + 14 < n && !logfile; i++) {
            if (!starts_at(buf, (int)n, i, m, 14)) continue;
            for (int j = i + 14; j + 5 <= n; j++)
                if (buf[j] == '/' && buf[j + 1] == 'r' && buf[j + 2] == 'u' && buf[j + 3] == 'n' && buf[j + 4] == '/') { logfile = 1; break; }
        }
    }
    // the property area stays open here (on Android it is only mapped)
    if (n > 0 && whole(buf, (int)n, "/dev/__properties__")) logfile = 1;
    if (size >= 9 && (logfile || (n > 5 && buf[0] == 'p' && buf[1] == 'i' && buf[2] == 'p' && buf[3] == 'e' && buf[4] == ':'))) {
        static const char dn[] = "/dev/null";
        for (int m = 0; m < 9; m++) buf[m] = dn[m];
        return 9;
    }
    // /dev/alarm is served by qemu as a memfd (patch 0096). A 6.0 zygote keeps the descriptor of its elapsed-time helper
    // open, and the fork check then aborts it: "Not whitelisted : /memfd:guest-alarm (deleted)", every app start fails.
    // Shown as /dev/null like the pipes above; the child reopens /dev/null over it and the helper falls back to the clock.
    {
        static const char al[] = "/memfd:guest-alarm";
        if (size >= 9 && n >= 18 && starts_at(buf, (int)n, 0, al, 18)) {
            static const char dn[] = "/dev/null";
            for (int m = 0; m < 9; m++) buf[m] = dn[m];
            return 9;
        }
    }
    // qemu's ownership table: shown as a framework jar (TreeFixer links system/framework/aemu-owners.jar to it)
    if (whole(buf, (int)n, "/dhd.owners")) {
        static const char as[] = "/system/framework/aemu-owners.jar";
        unsigned long m = 0;
        while (as[m] && m < size) { buf[m] = as[m]; m++; }
        n = (long)m;
    }
    return n;
}

// readlink does not terminate the name, and the host path it returned is longer than the guest one left behind: clear the tail
static long guest_fd_name(char *buf, long n, unsigned long size)
{
    long r = guest_fd_name_(buf, n, size);
    for (long i = r; i < n; i++) buf[i] = 0;
    return r;
}

EXPORT long readlink(const char *path, char *buf, unsigned long size)
{
    long r = sys3(NR_readlink, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}

EXPORT long readlinkat(int dirfd, const char *path, char *buf, unsigned long size)
{
    long r = sys4(NR_readlinkat, dirfd, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}

struct sockaddr_un_ { unsigned short family; char path[108]; };

EXPORT int getsockname(int fd, void *addr, unsigned *len)
{
    unsigned long args[3] = { (unsigned long)fd, (unsigned long)addr, (unsigned long)len };
    long r = sys3(NR_socketcall, SC_GETSOCKNAME, (long)args, 0);
    if (r < 0) return fail(r);
    struct sockaddr_un_ *u = (struct sockaddr_un_ *)addr;
    if (u && len && *len > 2 && u->family == 1 /* AF_UNIX */ && u->path[0]) {
        int n = (int)*len - 2;
        while (n > 0 && u->path[n - 1] == 0) n--;
        int m = strip_root(u->path, n);
        if (m != n) { u->path[m] = 0; *len = 2 + m + 1; }
    }
    return 0;
}

/* ---- guest RAM budget (added for AEmulator Plus, 2026-11-21) -------------------------------------------------------
 * The phone's /proc/meminfo and sysinfo() passed straight through: an x86 guest given 2 GB still saw all of the phone's memory.
 * The budget is AEMU_RAM_KB from the host; this shim imports nothing from libc but __errno, so it is read from
 * /proc/self/environ. Both answers are scaled like the ARM build's (aemushim.c): every kB value times guest / host total. */
static unsigned long env_ram_kb(void)
{
    static int done;
    static unsigned long val;
    if (done) return val;
    done = 1;
    long fd = sys3(NR_open, (long)"/proc/self/environ", 0, 0);
    if (fd < 0) return 0;
    static const char key[] = "AEMU_RAM_KB=";      // 12 characters
    char buf[1024];
    int mi = 0;                                    // characters of the key matched; 12 = reading digits; -1 = skip to the next entry
    unsigned long x = 0;
    int found = 0;
    for (; !found;) {
        long n = sys3(NR_read, fd, (long)buf, sizeof buf);
        if (n <= 0) { if (mi == 12) { val = x; } break; }
        for (long i = 0; i < n; i++) {
            char c = buf[i];
            if (mi == 12) {
                if (c >= '0' && c <= '9') { x = x * 10 + (unsigned long)(c - '0'); continue; }
                val = x; found = 1; break;
            }
            if (c == 0) { mi = 0; continue; }
            if (mi >= 0 && c == key[mi]) { mi++; continue; }
            mi = -1;
        }
    }
    sys3(NR_close, fd, 0, 0);
    return val;
}

// v * num / den without libgcc; v unchanged when the quotient would not fit 32 bits
static unsigned scale32(unsigned v, unsigned num, unsigned den)
{
    if (!den) return v;
    unsigned long long p = (unsigned long long)v * num;
    unsigned hi = (unsigned)(p >> 32), lo = (unsigned)p, q, rem;
    if (hi >= den) return v;
    __asm__("divl %4" : "=a"(q), "=d"(rem) : "a"(lo), "d"(hi), "r"(den));
    return q;
}

EXPORT int sysinfo(void *si)
{
    long r = sys3(NR_sysinfo, (long)si, 0, 0);
    if (r < 0) return fail(r);
    unsigned long guest = env_ram_kb();
    unsigned *w = (unsigned *)si;                  // 32-bit struct: totalram..freeswap are words 4..9, mem_unit word 13
    unsigned unit = w[13] ? w[13] : 1;
    unsigned total = scale32(w[4], unit, 1024);
    if (!guest || !total || total <= guest) return 0;
    for (int i = 4; i <= 9; i++) w[i] = scale32(scale32(w[i], unit, 1024), (unsigned)guest, total);
    w[13] = 1024;
    return 0;
}

static int path_is_meminfo(const char *p)
{
    static const char m[] = "/proc/meminfo";
    int i = 0;
    while (m[i] && p[i] == m[i]) i++;
    return !m[i] && !p[i];
}

// the fake /proc/meminfo as a memfd, or -1 to let the real file be opened
static long meminfo_fake(const char *path)
{
    if (!path || !path_is_meminfo(path)) return -1;
    unsigned long guest = env_ram_kb();
    if (!guest) return -1;
    static char in[8192], out[8192 + 512];
    long fd = sys3(NR_open, (long)path, 0, 0);
    if (fd < 0) return -1;
    long total = 0;
    for (;;) {
        long n = sys3(NR_read, fd, (long)(in + total), (long)sizeof in - 1 - total);
        if (n <= 0) break;
        total += n;
        if (total >= (long)sizeof in - 1) break;
    }
    sys3(NR_close, fd, 0, 0);
    if (total <= 0) return -1;
    in[total] = 0;
    unsigned long host = 0;
    const char *p = in;
    while (*p && (*p < '0' || *p > '9')) p++;
    while (*p >= '0' && *p <= '9') host = host * 10 + (unsigned long)(*p++ - '0');
    if (!host || guest >= host) return -1;
    long o = 0;
    p = in;
    while (*p && o < (long)sizeof out - 64) {
        const char *line = p, *e = p;
        while (*e && *e != 0x0a) e++;
        const char *d = line;
        while (d < e && (*d < '0' || *d > '9')) d++;
        const char *de = d;
        unsigned v = 0;
        while (de < e && *de >= '0' && *de <= '9') v = v * 10 + (unsigned)(*de++ - '0');
        int kb = de + 3 <= e && de[0] == ' ' && de[1] == 'k' && de[2] == 'B';
        if (d < e && kb) {
            unsigned s = scale32(v, (unsigned)guest, (unsigned)host);
            char num[12]; int k = 0;
            do { num[k++] = (char)('0' + (int)(s % 10)); s /= 10; } while (s && k < 11);
            int extra = k - (int)(de - d);          // keep the column: the new number ends where the old one did
            const char *pe = d;
            while (extra > 0 && pe - line >= 2 && pe[-1] == ' ' && pe[-2] == ' ') { pe--; extra--; }
            for (const char *c = line; c < pe; c++) out[o++] = *c;
            for (int j = (int)(de - d); j > k; j--) out[o++] = ' ';
            while (k) out[o++] = num[--k];
            for (const char *c = de; c < e; c++) out[o++] = *c;
        } else {
            for (const char *c = line; c < e; c++) out[o++] = *c;
        }
        if (*e) out[o++] = 0x0a;
        p = *e ? e + 1 : e;
    }
    long m = sys3(NR_memfd_create, (long)"meminfo", 0, 0);
    if (m < 0) return -1;
    long w = 0;
    while (w < o) { long n = sys3(NR_write, m, (long)(out + w), o - w); if (n <= 0) break; w += n; }
    sys3(NR_lseek, m, 0, 0);
    return m;
}

/* ---- mouse and keyboard (emulated evdev touchpad and keyboard, see vinput.h) ---------------------------------------
 * EventHub opens the two nodes the app leaves in /dev/input and asks them the EVIOC* questions; open() hands back a socket
 * to the app and ioctl() answers for it. Every other path and every other descriptor goes straight to the kernel call,
 * the same call bionic would have made (O_LARGEFILE added for open, as bionic does). */
#include "vinput.h"

#define VI_O_LARGEFILE 0100000

/* ---- virtual console (/dev/tty0) ----------------------------------------------------------------------------------
 * Android-x86 (5.x - 7.x) builds InputFlinger with -DCONSOLE_MANAGER -DANDROID_VT=7 on every variant but "user"
 * (frameworks/native/services/inputflinger/Android.mk). EventHub::getEvents() then does, on every pass,
 *     struct vt_stat vs;  int fd_vt = open("/dev/tty0", O_RDWR | O_SYNC);  if (fd_vt >= 0) { ioctl(fd_vt, VT_GETSTATE, &vs); close(fd_vt); }
 * and, after each read() from an event node, drops the events when `vs.v_active != ANDROID_VT`. The guest has no VT:
 * /dev/tty0 is not there, open() fails, `vs` stays uninitialised stack garbage and the comparison is a coin toss, so
 * touch, keys and mouse are swallowed. The guest is always "on" the Android VT, so /dev/tty0 is answered here.
 *
 * open("/dev/tty0") hands back a descriptor of /dev/null (a real descriptor: dup, fork, close and the zygote fd check
 * all behave, and guest_fd_name_ already shows it as /dev/null). ioctl() recognises the VT requests (0x5601..0x560f,
 * plain numbers; V4L2's 'V' requests carry a size and never match) on a descriptor that is a character device 1:3 and
 * answers them; every other request falls through to the kernel, which says ENOTTY for /dev/null, as it would for a
 * console opened without a VT driver. No state is kept, so nothing goes stale when the descriptor is closed and its
 * number reused. A real /dev/null that is handed a VT request is answered the same way; nobody sends one. */
#define VT_ANDROID        7           // ANDROID_VT of Android.mk; the guest never switches away from it
#define VT_GETMODE   0x5601
#define VT_SETMODE   0x5602
#define VT_GETSTATE  0x5603
#define VT_RELDISP   0x5605
#define VT_ACTIVATE  0x5606
#define VT_WAITACTIVE 0x5607
#define NR_fstat64     197
#define VI_O_ACCMODE        03
#define VI_O_NONBLOCK    04000
#define VI_O_CLOEXEC  02000000
#define VI_NOT_OURS    (-4096)        // same convention as vinput_open()

// raw result of the open, VI_NOT_OURS when the path is not the console
static long vt_open(const char *path, int flags)
{
    if (!vi_equal(path, "/dev/tty0")) return VI_NOT_OURS;
    // only the bits that mean something for a character device; O_CREAT/O_TRUNC/O_SYNC & co. must not reach /dev/null
    return sys3(NR_open, (long)"/dev/null", (flags & (VI_O_ACCMODE | VI_O_NONBLOCK | VI_O_CLOEXEC)) | VI_O_LARGEFILE, 0);
}

// i386 struct stat64: st_mode at byte 16, st_rdev (8 bytes, new_encode_dev) at byte 32, 96 bytes in all
static int vt_fd(int fd)
{
    unsigned st[24];
    if (sys3(NR_fstat64, fd, (long)st, 0) < 0) return 0;
    return (st[4] & 0170000) == 0020000 && st[8] == 0x0103 && st[9] == 0;   // S_IFCHR, makedev(1, 3)
}

// VI_NOT_OURS: not a request the console answers, let the kernel have it
static long vt_ioctl(unsigned req, void *arg)
{
    switch (req) {
    case VT_GETSTATE: {                         // struct vt_stat { u16 v_active, v_signal, v_state; }
        unsigned short *vs = arg;
        if (!vs) return fail(-14 /* EFAULT */);
        vs[0] = VT_ANDROID;
        vs[1] = 0;
        vs[2] = (unsigned short)(1u | 1u << VT_ANDROID);   // bit n = VT n in use: the system console and the Android VT
        return 0;
    }
    case VT_GETMODE: {                          // struct vt_mode { char mode, waitv; short relsig, acqsig, frsig; }: VT_AUTO
        unsigned char *vm = arg;
        if (!vm) return fail(-14 /* EFAULT */);
        for (int i = 0; i < 8; i++) vm[i] = 0;
        return 0;
    }
    case VT_SETMODE:
    case VT_RELDISP:
        return 0;
    case VT_ACTIVATE:                           // the argument is the VT number itself
    case VT_WAITACTIVE:
        return (long)arg == VT_ANDROID ? 0 : fail(-22 /* EINVAL */);
    }
    return VI_NOT_OURS;
}

EXPORT int open(const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100 /* O_CREAT */) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    { long mi = meminfo_fake(path); if (mi >= 0) return (int)mi; }
    { long t = vt_open(path, flags); if (t != VI_NOT_OURS) return t < 0 ? fail(t) : (int)t; }
    long v = vinput_open(path, flags);
    if (v != -4096) return v < 0 ? fail(v) : (int)v;
    long r = sys3(NR_open, (long)path, flags | VI_O_LARGEFILE, mode);
    return r < 0 ? fail(r) : (int)r;
}
EXPORT int open64(const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100 /* O_CREAT */) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    return open(path, flags, mode);
}
EXPORT int __open_2(const char *path, int flags) { return open(path, flags, 0); }

EXPORT int openat(int dirfd, const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100 /* O_CREAT */) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    { long mi = meminfo_fake(path); if (mi >= 0) return (int)mi; }
    { long t = vt_open(path, flags); if (t != VI_NOT_OURS) return t < 0 ? fail(t) : (int)t; }
    long v = vinput_open(path, flags);
    if (v != -4096) return v < 0 ? fail(v) : (int)v;
    long r = sys4(NR_openat, dirfd, (long)path, flags | VI_O_LARGEFILE, mode);
    return r < 0 ? fail(r) : (int)r;
}
EXPORT int openat64(int dirfd, const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100 /* O_CREAT */) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    return openat(dirfd, path, flags, mode);
}
EXPORT int __openat_2(int dirfd, const char *path, int flags) { return openat(dirfd, path, flags, 0); }

EXPORT int ioctl(int fd, int req, void *arg)
{
    if (((unsigned)req >> 8 & 255) == 'E') {
        int vk = vinput_fd(fd);
        if (vk) return vinput_ioctl(vk, (unsigned)req, arg);
    }
    if (((unsigned)req & ~255u) == 0x5600u && vt_fd(fd)) {
        long v = vt_ioctl((unsigned)req, arg);
        if (v != VI_NOT_OURS) return (int)v;
    }
    long r = sys3(NR_ioctl, fd, req, (long)arg);
    return r < 0 ? fail(r) : (int)r;
}

/* ---- extended attributes ------------------------------------------------------------------------------------------
 * The phone's kernel answers ENOSYS. 7.0+ installd reads and writes "security.selinux" around restorecon and tags app
 * dirs (user.default, user.inode_cache); any failure aborts create_app_data, the app's data dir is never made and
 * system_server dies in SettingsProvider ("Could not open database"). There is no SELinux in the guest: the label read
 * answers a fixed value (the same before and after, so nothing is relabelled), other names are "not set", writes
 * succeed and store nothing. The real call is always tried first. */
static int same_name(const char *n, const char *want)
{
    if (!n) return 0;
    for (; *want; n++, want++) if (*n != *want) return 0;
    return !*n;
}

static long emu_get(const char *name, void *value, unsigned long size)
{
    static const char ctx[] = "u:object_r:system_data_file:s0";
    if (!same_name(name, "security.selinux")) return fail(-ENODATA);
    unsigned long m = sizeof ctx;                         // the value includes its NUL, like the kernel's
    if (!size) return (long)m;
    if (size < m) return fail(-ERANGE);
    for (unsigned long k = 0; k < m; k++) ((char *)value)[k] = ctx[k];
    return (long)m;
}

static long do_get(long nr, long target, const char *name, void *value, unsigned long size)
{
    long r = sys4(nr, target, (long)name, (long)value, (long)size);
    if (r >= 0) return r;
    if (r == -ENOSYS) return emu_get(name, value, size);
    return fail(r);
}

static int do_set(long nr, long target, const char *name, const void *value, unsigned long size, int flags)
{
    long r = sys5(nr, target, (long)name, (long)value, (long)size, flags);
    if (r >= 0 || r == -ENOSYS || r == -EOPNOTSUPP) return 0;
    return fail(r);
}

// 7.0+ installd migrates app data between /data/user_de and /data/data on every boot unless the target dir carries
// user.default (it deletes one copy each time): that marker is reported as present. UserManager reads user.serial on
// the user dirs; any error but ENODATA ("not set yet, write it") makes system_server destroy all data of user 0.
EXPORT long getxattr(const char *path, const char *name, void *value, unsigned long size)
{
    if (same_name(name, "user.default")) return 0;
    if (same_name(name, "user.serial")) return fail(-ENODATA);
    return do_get(NR_getxattr, (long)path, name, value, size);
}
EXPORT long lgetxattr(const char *path, const char *name, void *value, unsigned long size) { return do_get(NR_lgetxattr, (long)path, name, value, size); }
EXPORT long fgetxattr(int fd, const char *name, void *value, unsigned long size)           { return do_get(NR_fgetxattr, fd, name, value, size); }
EXPORT int setxattr(const char *p, const char *n, const void *v, unsigned long s, int fl)  { return do_set(NR_setxattr, (long)p, n, v, s, fl); }
EXPORT int lsetxattr(const char *p, const char *n, const void *v, unsigned long s, int fl) { return do_set(NR_lsetxattr, (long)p, n, v, s, fl); }
EXPORT int fsetxattr(int fd, const char *n, const void *v, unsigned long s, int fl)        { return do_set(NR_fsetxattr, fd, n, v, s, fl); }

/* 7.0+ media services (mediacodec, mediaextractor) put themselves under a seccomp filter through minijail and abort
 * ("prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER): Invalid argument", exit 134): the translator cannot take a guest filter.
 * The app already runs under the phone's own filter, so the guest's is accepted and dropped. */
EXPORT int prctl(int option, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5)
{
    if (option == 22 /* PR_SET_SECCOMP */) return 0;
    long r = sys5(NR_prctl, option, (long)a2, (long)a3, (long)a4, (long)a5);
    return r >= 0 ? (int)r : fail(r);
}

/* 5.0+ marks every socket with its network id through fwmarkd (netd), which sets SO_MARK; an app has no CAP_NET_ADMIN,
 * the kernel refuses and every connect() fails (no TCP, no DNS). All sockets leave through the phone's one network, so
 * the mark is accepted and dropped. */
EXPORT int setsockopt(int fd, int level, int name, const void *val, unsigned int len)
{
    if (level == 1 /* SOL_SOCKET */ && name == 36 /* SO_MARK */) return 0;
    unsigned long args[5] = { (unsigned long)fd, (unsigned long)level, (unsigned long)name, (unsigned long)val, len };
    long r = sys3(NR_socketcall, SC_SETSOCKOPT, (long)args, 0);
    return r >= 0 ? (int)r : fail(r);
}

/* The *_ALARM clocks need CAP_WAKE_ALARM, which an app never has: 7.0+ AlarmManagerService then falls back to a handler
 * that throws on listener alarms and kills system_server. The plain clocks behave the same, without wakeups. */
EXPORT int timerfd_create(int clock, int flags)
{
    if (clock == 8 /* REALTIME_ALARM */) clock = 0 /* REALTIME */;
    else if (clock == 9 /* BOOTTIME_ALARM */) clock = 7 /* BOOTTIME */;
    long r = sys3(NR_timerfd_create, clock, flags, 0);
    return r < 0 ? fail(r) : (int)r;
}
