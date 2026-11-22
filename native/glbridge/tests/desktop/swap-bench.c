/*
 * What the guest's own CPU pays per frame and per call, measured where it matters: run as an i386 program under qemu-user
 * (the translator an x86 firmware runs in), against the host renderer of run-desktop.sh.
 *   sh run-bench.sh [path of a guest.c]
 * guest.c is included whole. Output: milliseconds per frame / microseconds per call, wall clock and the process's CPU time.
 */
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#ifndef GUEST_C
#define GUEST_C "../../guest.c"
#endif
#include GUEST_C

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio; va_list ap; va_start(ap, fmt);
    if (getenv("GL_VERBOSE")) { fprintf(stderr, "  [%s] ", tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr); }
    va_end(ap); return 0;
}
int __system_property_get(const char *name, char *value) { (void)name; value[0] = 0; return 0; }

enum { W = 540, H = 960 };
static uint8_t g_mem[W * H * 4];
static struct ANativeWindowBuffer g_buf;
static void inc(struct native_base *b) { (void)b; }
static void dec(struct native_base *b) { (void)b; }
static int fw_dequeue(struct ANativeWindow *w, struct ANativeWindowBuffer **b, int *fence) { (void)w; *b = &g_buf; *fence = -1; return 0; }
static int fw_queue(struct ANativeWindow *w, struct ANativeWindowBuffer *b, int fence) { (void)w; (void)b; (void)fence; return 0; }
static int fw_cancel(struct ANativeWindow *w, struct ANativeWindowBuffer *b, int fence) { (void)w; (void)b; (void)fence; return 0; }
static int fw_query(const struct ANativeWindow *w, int what, int *v) { (void)w; *v = what == 0 ? W : H; return 0; }
static int fw_perform(struct ANativeWindow *w, int op, ...) { (void)w; (void)op; return 0; }
static int fw_interval(struct ANativeWindow *w, int i) { (void)w; (void)i; return 0; }
static struct ANativeWindow g_win;

static double now(clockid_t c) { struct timespec t; clock_gettime(c, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }
#define T0() double w0 = now(CLOCK_MONOTONIC), c0 = now(CLOCK_PROCESS_CPUTIME_ID)
#define REPORT(what, n, unit, scale) printf("  %-44s wall %8.3f %s   cpu %8.3f %s\n", what, (now(CLOCK_MONOTONIC) - w0) * (scale) / (n), unit, (now(CLOCK_PROCESS_CPUTIME_ID) - c0) * (scale) / (n), unit)

#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_BLEND 0x0BE2
#define GL_ARRAY_BUFFER 0x8892
#define GL_FLOAT 0x1406
#define GL_TRIANGLES 4
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30

static void frames(EGLDisplay d, EGLSurface s, const char *name, int n) {
    for (int i = 0; i < 3; i++) { glClearColor(0.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT); eglSwapBuffers(d, s); }
    T0();
    for (int i = 0; i < n; i++) { glClearColor((float)(i & 7) / 7.f, 0.2f, 0.4f, 1.f); glClear(GL_COLOR_BUFFER_BIT); eglSwapBuffers(d, s); }
    REPORT(name, n, "ms/frame", 1);
}

int main(int argc, char **argv) {
    (void)argc; (void)argv;
    setvbuf(stdout, 0, _IOLBF, 0);
    int tries = 0;
    for (; tries < 50; tries++) { if (ts_get()) break; usleep(100000); }
    if (tries == 50) { printf("no host\n"); return 2; }
    memset(&g_buf, 0, sizeof g_buf);
    g_buf.common.incRef = inc; g_buf.common.decRef = dec;
    g_buf.width = W; g_buf.height = H; g_buf.stride = W; g_buf.handle = g_mem;
    g_win.common.incRef = inc; g_win.common.decRef = dec;
    g_win.dequeueBuffer = fw_dequeue; g_win.queueBuffer = fw_queue; g_win.cancelBuffer = fw_cancel; g_win.query = fw_query; g_win.perform = fw_perform; g_win.setSwapInterval = fw_interval;
    EGLDisplay d = eglGetDisplay(0);
    eglInitialize(d, 0, 0);
    const EGLint attribs[] = { 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3025, 0, EGL_NONE };
    EGLConfig cfg; EGLint n = 0;
    eglChooseConfig(d, attribs, &cfg, 1, &n);
    EGLSurface s = eglCreateWindowSurface(d, cfg, &g_win, 0);
    const EGLint c2[] = { 0x3098, 2, EGL_NONE };
    EGLContext c = eglCreateContext(d, cfg, 0, c2);
    if (!eglMakeCurrent(d, s, s, c) || !c) { printf("eglMakeCurrent failed: context %p error 0x%x\n", c, eglGetError()); return 3; }
    printf("context current, GL_VERSION %s\n", (const char *)glGetString(0x1F02));

    printf("eglSwapBuffers, %dx%d\n", W, H);
    g_buf.format = 2; g_buf.usage_deprecated = 0x933;
    frames(d, s, "RGBX_8888 window (an app's)", 40);
    g_buf.format = 1;
    frames(d, s, "RGBA_8888 window", 40);
    g_buf.format = 4; g_buf.usage_deprecated = 0x933;
    frames(d, s, "RGB_565 window (the framebuffer's)", 40);

    printf("per call\n");
    { int k = 100000; T0(); for (int i = 0; i < k; i++) { glEnable(GL_BLEND); glDisable(GL_BLEND); } REPORT("glEnable/glDisable (a call that only encodes)", 2.0 * k, "us/call", 1000); }
    glFinish();
    GLuint vb; glGenBuffers(1, &vb);
    /* draws whose vertices are in the program's memory (hwui: text, shadows, shapes) */
    GLuint vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER), pr = glCreateProgram();
    const char *vsrc = "attribute vec2 p; void main() { gl_Position = vec4(p, 0.0, 1.0); }", *fsrc = "precision mediump float; void main() { gl_FragColor = vec4(1.0); }";
    glShaderSource(vs, 1, &vsrc, 0); glCompileShader(vs); glShaderSource(fs, 1, &fsrc, 0); glCompileShader(fs);
    glAttachShader(pr, vs); glAttachShader(pr, fs); glBindAttribLocation(pr, 0, "p"); glLinkProgram(pr); glUseProgram(pr);
    static float verts[8192];
    for (int i = 0; i < 8192; i++) verts[i] = (float)((i * 37) % 100) / 100.f - 0.5f;
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, 0, 0, verts);
    for (int sz = 24; sz <= 8192; sz *= 16) {
        int k = 2000;
        char name[96];
        snprintf(name, sizeof name, "glDrawArrays, client vertices, %d floats", sz < 8192 ? sz : sz);
        glDrawArrays(GL_TRIANGLES, 0, 3); glFinish();
        T0();
        for (int i = 0; i < k; i++) { verts[0] = (float)i; glDrawArrays(GL_TRIANGLES, 0, sz / 2 / 3 * 3); }
        glFinish();
        REPORT(name, k, "us/draw", 1000);
    }
    eglMakeCurrent(d, 0, 0, 0);
    return 0;
}
