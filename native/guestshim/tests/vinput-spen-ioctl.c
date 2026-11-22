/* Host-side check of the emulated S-Pen digitizer's evdev answers (vinput.h). No guest, no sockets, no root:
 *   gcc -m64 -Wall -o /tmp/spen-ioctl tests/vinput-spen-ioctl.c && /tmp/spen-ioctl
 * sys3() and fail() are stubbed; only vinput_ioctl() is exercised. */
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

int main(void) {
    unsigned char b[96]; int abs[6];
    memset(b, 0, sizeof b);
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x06, 80), b) > 0 && !strcmp((char *)b, "sec_e-pen"));
    memset(b, 0, sizeof b); CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x09, 8), b) == 8 && has(b, 1) && !has(b, 0));   /* DIRECT only */
    memset(b, 0, sizeof b); vinput_ioctl(VI_SPEN, EV_IOC(0x20, 8), b);
    CHECK(has(b, 0) && has(b, 1) && has(b, 3) && !has(b, 2));                                                  /* SYN KEY ABS, no REL */
    memset(b, 0, sizeof b); vinput_ioctl(VI_SPEN, EV_IOC(0x21, 96), b);
    CHECK(has(b, 320) && has(b, 330) && has(b, 331) && !has(b, 272));                                           /* pen, touch, stylus; no mouse */
    memset(b, 0, sizeof b); vinput_ioctl(VI_SPEN, EV_IOC(0x23, 8), b);
    CHECK(has(b, 0) && has(b, 1) && has(b, 24) && has(b, 25) && has(b, 26) && has(b, 27) && !has(b, 53));      /* single-touch axes only */
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x40 + 0, 24), abs) == 0 && abs[2] == VI_SPEN_XY_MAX);
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x40 + 1, 24), abs) == 0 && abs[2] == VI_SPEN_XY_MAX);
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x40 + 24, 24), abs) == 0 && abs[2] == VI_SPEN_PRESSURE_MAX && abs[1] == 0);
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x40 + 25, 24), abs) == 0 && abs[2] == VI_SPEN_DISTANCE_MAX);
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x40 + 26, 24), abs) == 0 && abs[1] == -63 && abs[2] == 63);
    CHECK(vinput_ioctl(VI_SPEN, EV_IOC(0x40 + 53, 24), abs) == -22);                                            /* no multi-touch axis */
    /* the old devices are unchanged */
    memset(b, 0, sizeof b); CHECK(vinput_ioctl(VI_PAD, EV_IOC(0x06, 80), b) > 0 && !strcmp((char *)b, "AEmulator Touchpad"));
    memset(b, 0, sizeof b); vinput_ioctl(VI_PAD, EV_IOC(0x23, 8), b); CHECK(has(b, 53) && !has(b, 24));
    memset(b, 0, sizeof b); CHECK(vinput_ioctl(VI_KBD, EV_IOC(0x06, 80), b) > 0 && !strcmp((char *)b, "AEmulator Keyboard"));
    memset(b, 0, sizeof b); vinput_ioctl(VI_KBD, EV_IOC(0x20, 8), b); CHECK(!has(b, 3));
    CHECK(!strcmp(vi_socket_of(VI_SPEN_NODE), VI_SPEN_SOCKET) && !strcmp(vi_socket_of(VI_PAD_NODE), VI_PAD_SOCKET) && vi_socket_of("/dev/input/event0") == 0);
    puts(bad ? "FAILED" : "ok");
    return bad != 0;
}
