/* Host-side check of the emulated Xperia Play slider's evdev answers (vinput.h). No guest, no sockets, no root:
 *   gcc -m64 -Wall -o /tmp/slider-ioctl tests/vinput-slider-ioctl.c && /tmp/slider-ioctl
 * sys3() and fail() are stubbed; only vinput_ioctl() and the path/socket helpers are exercised. */
#include <stdio.h>
#include <string.h>
static long sys3(long n, long a, long b, long c) { (void)n; (void)a; (void)b; (void)c; return -38; }
static int fail(int e) { return e; }
#include "../vinput.h"

#define IOC_READ 0x80000000u
#define EV_IOC(nr, len) (IOC_READ | ((unsigned)(len) << 16) | ('E' << 8) | (nr))
static int bad;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); bad++; } } while (0)
static int has(const unsigned char *b, unsigned bit) { return (b[bit / 8] >> (bit % 8)) & 1; }
static int any(const unsigned char *b, unsigned len) { for (unsigned i = 0; i < len; i++) if (b[i]) return 1; return 0; }

int main(void) {
    unsigned char b[96]; unsigned short id[4]; int abs[6];
    /* identity */
    memset(b, 0, sizeof b);
    CHECK(vinput_ioctl(VI_SLIDER, EV_IOC(0x06, 80), b) > 0 && !strcmp((char *)b, "AEmulator Slider"));
    memset(b, 0, sizeof b); CHECK(vinput_ioctl(VI_SLIDER, EV_IOC(0x07, 80), b) > 0 && !strcmp((char *)b, "aemu/slider"));
    memset(b, 0, sizeof b); CHECK(vinput_ioctl(VI_SLIDER, EV_IOC(0x08, 80), b) > 0 && !strcmp((char *)b, "aemu-slider"));
    CHECK(vinput_ioctl(VI_SLIDER, EV_IOC(0x02, 8), id) == 0 && id[0] == 6 && id[2] == 5);
    /* a switch and nothing else: EV_SYN + EV_SW, no keys (the guest must not take it for a keyboard), no axes */
    memset(b, 0, sizeof b); vinput_ioctl(VI_SLIDER, EV_IOC(0x20, 8), b);
    CHECK(has(b, 0) && has(b, 5) && !has(b, 1) && !has(b, 2) && !has(b, 3));
    memset(b, 0, sizeof b); vinput_ioctl(VI_SLIDER, EV_IOC(0x21, 96), b); CHECK(!any(b, 96));      /* EV_KEY bits: none */
    memset(b, 0, sizeof b); vinput_ioctl(VI_SLIDER, EV_IOC(0x23, 8), b); CHECK(!any(b, 8));        /* EV_ABS bits: none */
    memset(b, 0, sizeof b); vinput_ioctl(VI_SLIDER, EV_IOC(0x20 + 5, 4), b);
    CHECK(has(b, VI_SLIDER_SW_LID) && !has(b, 1) && !has(b, 2));                                    /* SW_LID only */
    /* EVIOCGSW: shut at boot; every other device reports no switch pressed */
    memset(b, 0, sizeof b); CHECK(vinput_ioctl(VI_SLIDER, EV_IOC(0x1b, 8), b) == 8 && has(b, VI_SLIDER_SW_LID));
    memset(b, 0, sizeof b); vinput_ioctl(VI_SPEN, EV_IOC(0x1b, 8), b); CHECK(!any(b, 8));
    memset(b, 0, sizeof b); vinput_ioctl(VI_KBD, EV_IOC(0x1b, 8), b); CHECK(!any(b, 8));
    memset(b, 0, sizeof b); vinput_ioctl(VI_PAD, EV_IOC(0x25, 4), b); CHECK(!any(b, 4));            /* the others have no switch bits */
    CHECK(vinput_ioctl(VI_SLIDER, EV_IOC(0x40 + 0, 24), abs) == -25);                                /* no axes at all */
    CHECK(vinput_ioctl(VI_SLIDER, 0x90 /* EVIOCGRAB */, b) == 0);
    /* paths and sockets */
    CHECK(!strcmp(vi_socket_of(VI_SLIDER_NODE), VI_SLIDER_SOCKET));
    CHECK(!strcmp(vi_socket_of_kind(VI_SLIDER), "/dev/aemu_slider") && !strcmp(vi_socket_of_kind(VI_SPEN), VI_SPEN_SOCKET));
    CHECK(!strcmp(vi_socket_of(VI_SPEN_NODE), VI_SPEN_SOCKET) && !strcmp(vi_socket_of(VI_PAD_NODE), VI_PAD_SOCKET));
    CHECK(!strcmp(vi_socket_of(VI_KBD_NODE), VI_KBD_SOCKET) && vi_socket_of("/dev/input/event0") == 0);
    /* the old devices are unchanged */
    memset(b, 0, sizeof b); vinput_ioctl(VI_KBD, EV_IOC(0x20, 8), b); CHECK(has(b, 1) && !has(b, 3) && !has(b, 5));
    memset(b, 0, sizeof b); vinput_ioctl(VI_KBD, EV_IOC(0x21, 96), b); CHECK(has(b, 30) && has(b, 248));
    memset(b, 0, sizeof b); vinput_ioctl(VI_PAD, EV_IOC(0x21, 96), b); CHECK(has(b, 272) && has(b, 330) && !has(b, 320));
    memset(b, 0, sizeof b); vinput_ioctl(VI_SPEN, EV_IOC(0x21, 96), b); CHECK(has(b, 320) && has(b, 331) && !has(b, 272));
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x02, 8), id) == 0 && id[2] == 4);
    CHECK(vinput_ioctl(VI_PAD, EV_IOC(0x02, 8), id) == 0 && id[2] == 2);
    CHECK(vinput_ioctl(VI_KBD, EV_IOC(0x02, 8), id) == 0 && id[2] == 3);
    puts(bad ? "FAILED" : "ok");
    return bad != 0;
}
