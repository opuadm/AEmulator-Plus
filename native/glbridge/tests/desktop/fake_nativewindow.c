/* libnativewindow.so for the desktop test: guest.c locks a window buffer through AHardwareBuffer_lock; the buffer's
 * "handle" is simply its pixel memory here. (The layout is guest.c's own struct ANativeWindowBuffer.) */
#include <stdint.h>
struct native_base { int magic, version; void *reserved[4]; void (*incRef)(struct native_base *); void (*decRef)(struct native_base *); };
struct ANativeWindowBuffer {
    struct native_base common;
    int width, height, stride, format, usage_deprecated;
    uintptr_t layerCount;
    void *reserved[1];
    const void *handle;
    uint64_t usage;
};
int AHardwareBuffer_lock(void *b, uint64_t usage, int fence, const void *rect, void **va) {
    (void)usage; (void)fence; (void)rect;
    *va = (void *)((struct ANativeWindowBuffer *)b)->handle;
    return 0;
}
int AHardwareBuffer_unlock(void *b, int *fence) { (void)b; (void)fence; return 0; }
