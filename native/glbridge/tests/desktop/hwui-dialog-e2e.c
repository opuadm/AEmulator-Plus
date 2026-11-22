/*
 * What Android 4.4's hardware renderer (hwui) does for a Dialog, end to end: guest.c -> socket -> host.c on llvmpipe.
 * A translucent RGBA_8888 window; the panel is a nine-patch drawn from a VBO (glBufferData(NULL) + glBufferSubData,
 * attribute offsets 0 and 8, glDrawArrays), the text comes from client arrays and the shared index buffer, both with
 * premultiplied blending over a buffer cleared to (0,0,0,0). The pixels that come back through eglSwapBuffers are checked.
 */
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include "../../guest.c"

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio; char line[512];
    va_list ap; va_start(ap, fmt); vsnprintf(line, sizeof line, fmt, ap); va_end(ap);
    if (getenv("GL_VERBOSE") || strstr(line, "carry") || strstr(line, "host") || strstr(line, "drop")) fprintf(stderr, "  [%s] %s\n", tag, line);
    return 0;
}
int __system_property_get(const char *name, char *value) { (void)name; value[0] = 0; return 0; }

static int bad, checks;
#define CHECK(c) do { checks++; if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); bad++; } } while (0)
enum { W = 320, H = 240 };
struct fwin { struct ANativeWindow win; struct ANativeWindowBuffer buf; uint8_t *mem; int w, h, stride, queued; };
static void inc(struct native_base *b) { (void)b; }
static void dec(struct native_base *b) { (void)b; }
static int fw_dequeue(struct ANativeWindow *w, struct ANativeWindowBuffer **b, int *fence) { struct fwin *f = (struct fwin *)w; *b = &f->buf; *fence = -1; return 0; }
static int fw_queue(struct ANativeWindow *w, struct ANativeWindowBuffer *b, int fence) { struct fwin *f = (struct fwin *)w; (void)b; (void)fence; f->queued++; return 0; }
static int fw_query(const struct ANativeWindow *w, int what, int *v) { const struct fwin *f = (const struct fwin *)w; *v = what == 0 ? f->w : f->h; return 0; }
static int fw_perform(struct ANativeWindow *w, int op, ...) { (void)w; (void)op; return 0; }
static int fw_cancel(struct ANativeWindow *w, struct ANativeWindowBuffer *b, int fence) { (void)w; (void)b; (void)fence; return 0; }
static int fw_interval(struct ANativeWindow *w, int i) { (void)w; (void)i; return 0; }
static void fwin_init(struct fwin *f, int w, int h, int format) {
    memset(f, 0, sizeof *f);
    f->w = w; f->h = h; f->stride = w; f->mem = calloc(1, (size_t)w * h * 4);
    f->buf.common.incRef = inc; f->buf.common.decRef = dec;
    f->buf.width = w; f->buf.height = h; f->buf.stride = w; f->buf.format = format; f->buf.usage_deprecated = 0x933; f->buf.handle = f->mem;
    f->win.common.incRef = inc; f->win.common.decRef = dec;
    f->win.dequeueBuffer = fw_dequeue; f->win.queueBuffer = fw_queue; f->win.query = fw_query; f->win.perform = fw_perform; f->win.setSwapInterval = fw_interval; f->win.cancelBuffer = fw_cancel;
}
static struct fwin *g_cur;
static void px(int x, int y, int *r, int *g, int *b, int *a) { const uint8_t *p = g_cur->mem + ((size_t)y * g_cur->w + x) * 4; *r = p[0]; *g = p[1]; *b = p[2]; *a = p[3]; }
static int is(const char *what, int x, int y, int r, int g, int b, int a, int tol) {
    int pr, pg, pb, pa; px(x, y, &pr, &pg, &pb, &pa);
#define NR(p, q) ((p) >= (q) - tol && (p) <= (q) + tol)
    if (NR(pr, r) && NR(pg, g) && NR(pb, b) && NR(pa, a)) return 1;
    printf("    %s: pixel (%d,%d) is %d,%d,%d,%d, wanted %d,%d,%d,%d\n", what, x, y, pr, pg, pb, pa, r, g, b, a);
    return 0;
}

#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_TEXTURE_2D 0x0DE1
#define GL_UNSIGNED_BYTE 0x1401
#define GL_UNSIGNED_SHORT 0x1403
#define GL_FLOAT 0x1406
#define GL_RGBA 0x1908
#define GL_ALPHA 0x1906
#define GL_TRIANGLES 4
#define GL_BLEND 0x0BE2
#define GL_ONE 1
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
#define GL_SCISSOR_TEST 0x0C11
#define GL_ARRAY_BUFFER 0x8892
#define GL_ELEMENT_ARRAY_BUFFER 0x8893
#define GL_DYNAMIC_DRAW 0x88E8
#define GL_STATIC_DRAW 0x88E4
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_NEAREST 0x2600
#define GL_LINEAR 0x2601
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_UNPACK_ALIGNMENT 0x0CF5

static GLuint prog(void) {
    GLuint vs = glCreateShader(0x8B31), fs = glCreateShader(0x8B30), pr = glCreateProgram();
    const char *vsrc = "attribute vec4 position; attribute vec2 texCoords; varying vec2 outTexCoords; void main() { outTexCoords = texCoords; gl_Position = position; }";
    const char *fsrc = "precision mediump float; varying vec2 outTexCoords; uniform sampler2D baseSampler; uniform vec4 color; uniform float text;"
                       "void main() { gl_FragColor = text > 0.5 ? color * texture2D(baseSampler, outTexCoords).a : texture2D(baseSampler, outTexCoords); }";
    glShaderSource(vs, 1, &vsrc, 0); glCompileShader(vs); glShaderSource(fs, 1, &fsrc, 0); glCompileShader(fs);
    glAttachShader(pr, vs); glAttachShader(pr, fs);
    glBindAttribLocation(pr, 0, "position"); glBindAttribLocation(pr, 1, "texCoords");
    glLinkProgram(pr);
    GLint ok = 0; glGetProgramiv(pr, 0x8B82, &ok); CHECK(ok);
    return pr;
}
/* clip space of a pixel rectangle in a window of ww x wh (y down) */
static void rect(float *v, float ww, float wh, float l, float t, float r, float b) {
    float x0 = l / ww * 2 - 1, x1 = r / ww * 2 - 1, y0 = 1 - t / wh * 2, y1 = 1 - b / wh * 2;
    float q[6][4] = { { x0, y0, 0, 0 }, { x1, y0, 1, 0 }, { x0, y1, 0, 1 }, { x1, y0, 1, 0 }, { x1, y1, 1, 1 }, { x0, y1, 0, 1 } };
    memcpy(v, q, sizeof q);
}

/* what hwui keeps in its caches for the life of the context, whatever window is drawn */
static GLuint g_pr, g_tex[2], g_vbo, g_ibo;
static GLint g_uc, g_ut;
static void make_resources(void) {
    g_pr = prog(); glUseProgram(g_pr);
    g_uc = glGetUniformLocation(g_pr, "color"); g_ut = glGetUniformLocation(g_pr, "text");
    glUniform1i(glGetUniformLocation(g_pr, "baseSampler"), 0);
    glGenTextures(2, g_tex);
    uint8_t bmp[3 * 3 * 4]; for (int i = 0; i < 9; i++) { bmp[i * 4] = 220; bmp[i * 4 + 1] = 220; bmp[i * 4 + 2] = 220; bmp[i * 4 + 3] = 255; }
    glBindTexture(GL_TEXTURE_2D, g_tex[0]);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 3, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, bmp);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    uint8_t gl8[8 * 8]; memset(gl8, 255, sizeof gl8);
    glBindTexture(GL_TEXTURE_2D, g_tex[1]);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, 8, 8, 0, GL_ALPHA, GL_UNSIGNED_BYTE, gl8);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenBuffers(1, &g_vbo); glGenBuffers(1, &g_ibo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, 262144, 0, GL_DYNAMIC_DRAW);             /* PatchCache: empty at first */
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    uint16_t idx[6 * 16]; for (int q = 0; q < 16; q++) { static const int o[6] = { 0, 1, 2, 2, 1, 3 }; for (int k = 0; k < 6; k++) idx[q * 6 + k] = (uint16_t)(q * 4 + o[k]); }
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g_ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof idx, idx, GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
}
static int g_patches;      /* patches written into the VBO so far (a cache entry each) */
/* One frame of a window: clear, the panel (a patch from the VBO), text from memory, a second patch. The panel covers the
 * window's middle; (l,t,r,b) is it in window pixels. */
static void draw_frame(struct fwin *f, int l, int t, int r, int b) {
    int ww = f->w, wh = f->h;
    memset(f->mem, 0x55, (size_t)ww * wh * 4);
    glEnable(GL_SCISSOR_TEST); glScissor(0, 0, ww, wh); glClear(GL_COLOR_BUFFER_BIT);
    glViewport(0, 0, ww, wh);
    glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glUseProgram(g_pr);
    /* the patch cache writes the mesh of a patch it has not seen (a new size: each window has its own) */
    float mesh[12 * 4];
    rect(mesh, ww, wh, (float)l, (float)t, (float)r, (float)b);
    rect(mesh + 24, ww, wh, (float)l + 20, (float)b - 40, (float)l + 100, (float)b - 10);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferSubData(GL_ARRAY_BUFFER, g_patches * (long)sizeof mesh, sizeof mesh, mesh);
    long at = g_patches++ * (long)sizeof mesh;
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    glBindTexture(GL_TEXTURE_2D, g_tex[0]);
    glUniform1f(g_ut, 0.f);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, 0, 16, (const void *)at);
    glVertexAttribPointer(1, 2, GL_FLOAT, 0, 16, (const void *)(at + 8));
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    float tv[4 * 4];
    { float q[24]; rect(q, ww, wh, (float)l + 20, (float)t + 20, (float)r - 20, (float)t + 40);
      float c[4][4] = { { q[0], q[1], 0, 0 }, { q[4], q[5], 1, 0 }, { q[8], q[9], 0, 1 }, { q[16], q[17], 1, 1 } };
      memcpy(tv, c, sizeof c); }
    glBindTexture(GL_TEXTURE_2D, g_tex[1]);
    glUniform1f(g_ut, 1.f); glUniform4f(g_uc, 0.f, 0.f, 0.f, 1.f);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g_ibo);
    glVertexAttribPointer(0, 2, GL_FLOAT, 0, 16, tv);
    glVertexAttribPointer(1, 2, GL_FLOAT, 0, 16, tv + 2);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    glBindTexture(GL_TEXTURE_2D, g_tex[0]);
    glUniform1f(g_ut, 0.f);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glVertexAttribPointer(0, 2, GL_FLOAT, 0, 16, (const void *)at);
    glVertexAttribPointer(1, 2, GL_FLOAT, 0, 16, (const void *)(at + 8));
    glDrawArrays(GL_TRIANGLES, 6, 6);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}
static void check_frame(const char *who, struct fwin *f, int l, int t, int r, int b, int opaque_bg) {
    char m[96];
    g_cur = f;
    if (l > 0) { snprintf(m, sizeof m, "%s: outside the panel", who); CHECK(is(m, 2, 2, 0, 0, 0, 0, 1)); }
    snprintf(m, sizeof m, "%s: panel", who);
    CHECK(is(m, l + 5, t + 50, 220, 220, 220, 255, 2));
    CHECK(is(m, r - 5, b - 5, 220, 220, 220, 255, 2));
    snprintf(m, sizeof m, "%s: text", who);
    CHECK(is(m, (l + r) / 2, t + 30, 0, 0, 0, 255, 2));
    snprintf(m, sizeof m, "%s: button patch", who);
    CHECK(is(m, l + 60, b - 25, 220, 220, 220, 255, 2));
}


/* A real nine-patch: a 3x3 grid of quads (54 vertices) written far into the patch cache's VBO, its bitmap uploaded with
 * GL_UNPACK_ROW_LENGTH wider than the bitmap (rowBytes padding), a 1-pixel-tall divider texture, and a draw of it. */
static void patch_extra(struct fwin *f, EGLDisplay d, EGLSurface s) {
    int ww = f->w, wh = f->h;
    glEnable(GL_SCISSOR_TEST); glScissor(0, 0, ww, wh); glClear(GL_COLOR_BUFFER_BIT); glViewport(0, 0, ww, wh);
    glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glUseProgram(g_pr); glUniform1f(g_ut, 0.f);
    GLuint t[2]; glGenTextures(2, t);
    uint8_t pad[3 * 4 * 4]; memset(pad, 0, sizeof pad);                       /* 3x3, rows padded to 4 pixels */
    for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) { uint8_t *p = pad + (y * 4 + x) * 4; p[0] = 200; p[1] = 100; p[2] = 50; p[3] = 255; }
    glBindTexture(GL_TEXTURE_2D, t[0]);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4); glPixelStorei(0x0CF2, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 3, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, pad);
    glPixelStorei(0x0CF2, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    uint8_t div[1 * 1 * 4] = { 0, 255, 0, 255 };                                /* 1x1 */
    glBindTexture(GL_TEXTURE_2D, t[1]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, div);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    float m[54 * 4]; int k = 0;
    for (int cy = 0; cy < 3; cy++) for (int cx = 0; cx < 3; cx++) {
        float q[24]; rect(q, ww, wh, 10 + cx * 60.f, 10 + cy * 30.f, 70 + cx * 60.f, 40 + cy * 30.f);
        for (int v = 0; v < 6; v++) { m[k * 4] = q[v * 4]; m[k * 4 + 1] = q[v * 4 + 1]; m[k * 4 + 2] = (cx + .5f) / 3; m[k * 4 + 3] = (cy + .5f) / 3; k++; }
    }
    float line[6 * 4]; rect(line, ww, wh, 10, 100, 190, 101);
    for (int v = 0; v < 6; v++) { line[v * 4 + 2] = .5f; line[v * 4 + 3] = .5f; }
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferSubData(GL_ARRAY_BUFFER, 70000, sizeof m, m);
    glBufferSubData(GL_ARRAY_BUFFER, 70000 + sizeof m, sizeof line, line);
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glBindTexture(GL_TEXTURE_2D, t[0]);
    glVertexAttribPointer(0, 2, GL_FLOAT, 0, 16, (const void *)70000);
    glVertexAttribPointer(1, 2, GL_FLOAT, 0, 16, (const void *)70008);
    glDrawArrays(GL_TRIANGLES, 0, 54);
    glBindTexture(GL_TEXTURE_2D, t[1]);
    glVertexAttribPointer(0, 2, GL_FLOAT, 0, 16, (const void *)(70000 + sizeof m));
    glVertexAttribPointer(1, 2, GL_FLOAT, 0, 16, (const void *)(70008 + sizeof m));
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    eglSwapBuffers(d, s);
    g_cur = f;
    CHECK(is("3x3 patch, padded rows, far offset", 40, 25, 200, 100, 50, 255, 2));
    CHECK(is("3x3 patch, last cell", 160, 85, 200, 100, 50, 255, 2));
    CHECK(is("1-pixel divider", 100, 100, 0, 255, 0, 255, 2));
}

int main(void) {
    int tries = 0;
    setvbuf(stdout, 0, _IOLBF, 0);
    for (; tries < 50; tries++) { struct ts *T = ts_get(); if (T) break; usleep(100000); }
    if (tries == 50) { printf("no host\n"); return 2; }
    static struct fwin act, dlg, dlg2;
    fwin_init(&act, W, H, 1);              /* the activity: RGBA_8888, opaque window */
    fwin_init(&dlg, 200, 120, 1);          /* its dialog: translucent, smaller */
    fwin_init(&dlg2, 180, 100, 1);         /* a second dialog later */
    CHECK(solo());
    EGLDisplay d = eglGetDisplay(0);
    CHECK(eglInitialize(d, 0, 0));
    EGLConfig cfg; EGLint n = 0;
    const EGLint plain[] = { 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 0, 0x3027, 0x3038, 0x3026, 8, 0x3033, 0x4, EGL_NONE };
    CHECK(eglChooseConfig(d, plain, &cfg, 1, &n) && n == 1);
    const EGLint c2[] = { 0x3098, 2, EGL_NONE };
    EGLContext c = eglCreateContext(d, cfg, 0, c2);      /* hwui: one context per thread, shared by every window of it */
    EGLSurface sa = eglCreateWindowSurface(d, cfg, &act.win, 0);
    CHECK(sa != 0 && c != 0 && eglMakeCurrent(d, sa, sa, c));
    make_resources();

    printf("the activity\n");
    for (int i = 0; i < 2; i++) { draw_frame(&act, 20, 20, 300, 220); eglSwapBuffers(d, sa); check_frame("activity", &act, 20, 20, 300, 220, 1); }

    printf("a dialog on the same context\n");
    EGLSurface sd = eglCreateWindowSurface(d, cfg, &dlg.win, 0);
    CHECK(sd != 0 && eglMakeCurrent(d, sd, sd, c));
    for (int i = 0; i < 3; i++) { draw_frame(&dlg, 0, 0, 200, 120); eglSwapBuffers(d, sd); check_frame("dialog", &dlg, 0, 0, 200, 120, 0); }

    printf("back to the activity, then the dialog again\n");
    CHECK(eglMakeCurrent(d, sa, sa, c));
    draw_frame(&act, 20, 20, 300, 220); eglSwapBuffers(d, sa); check_frame("activity", &act, 20, 20, 300, 220, 1);
    CHECK(eglMakeCurrent(d, sd, sd, c));
    draw_frame(&dlg, 0, 0, 200, 120); eglSwapBuffers(d, sd); check_frame("dialog again", &dlg, 0, 0, 200, 120, 0);

    printf("the dialog goes, a second one comes\n");
    CHECK(eglMakeCurrent(d, sa, sa, c));
    eglDestroySurface(d, sd);
    EGLSurface sd2 = eglCreateWindowSurface(d, cfg, &dlg2.win, 0);
    CHECK(sd2 != 0 && eglMakeCurrent(d, sd2, sd2, c));
    for (int i = 0; i < 2; i++) { draw_frame(&dlg2, 0, 0, 180, 100); eglSwapBuffers(d, sd2); check_frame("second dialog", &dlg2, 0, 0, 180, 100, 0); }
    printf("a real nine-patch and a divider\n");
    CHECK(eglMakeCurrent(d, sa, sa, c));
    patch_extra(&act, d, sa);
    printf("hwui dialog end to end: %d checks, %d failed\n", checks, bad);
    return bad != 0;
}
