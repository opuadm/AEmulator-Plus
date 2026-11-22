/*
 * GLES 1 end to end on a desktop: guest.c (with gles1.h) -> the unix socket -> host.c on Mesa's llvmpipe.
 *   sh run-desktop.sh
 * guest.c is included whole (the statics can be reached); bionic's two imports are stood in for, and the window is a
 * fake ANativeWindow whose buffer is plain memory. The scenes are what Android 4.x's boot animation and the old
 * games do; every one is checked by the pixels that come back through eglSwapBuffers.
 */
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include "../../guest.c"

static int g_link_fail, g_missing;
int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio;
    char line[512];
    va_list ap; va_start(ap, fmt); vsnprintf(line, sizeof line, fmt, ap); va_end(ap);
    if (strstr(line, "did not link")) g_link_fail++;
    if (strstr(line, "does not carry")) g_missing++;
    if (getenv("GL_VERBOSE") || strstr(line, "GLES 1") || strstr(line, "carry") || strstr(line, "host")) fprintf(stderr, "  [%s] %s\n", tag, line);
    return 0;
}
int __system_property_get(const char *name, char *value) { (void)name; value[0] = 0; return 0; }

static int bad, checks;
#define CHECK(c) do { checks++; if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); bad++; } } while (0)

/* ---- the fake window: one buffer of memory, RGBX_8888 as a boot animation's window is ---- */
enum { W = 320, H = 240 };
static uint8_t g_mem[(W + 32) * H * 4];
static struct ANativeWindowBuffer g_buf;
static int g_queued;
static void inc(struct native_base *b) { (void)b; }
static void dec(struct native_base *b) { (void)b; }
static int fw_dequeue(struct ANativeWindow *w, struct ANativeWindowBuffer **b, int *fence) { (void)w; *b = &g_buf; *fence = -1; return 0; }
static int fw_queue(struct ANativeWindow *w, struct ANativeWindowBuffer *b, int fence) { (void)w; (void)b; (void)fence; g_queued++; return 0; }
static int fw_query(const struct ANativeWindow *w, int what, int *v) { (void)w; *v = what == 0 ? W : H; return 0; }
static int fw_perform(struct ANativeWindow *w, int op, ...) { (void)w; (void)op; return 0; }
static int fw_cancel(struct ANativeWindow *w, struct ANativeWindowBuffer *b, int fence) { (void)w; (void)b; (void)fence; return 0; }
static int fw_interval(struct ANativeWindow *w, int i) { (void)w; (void)i; return 0; }
static struct ANativeWindow g_win;

/* the pixel at (x, y) counted from the top left, as the compositor would read the buffer */
static void px(int x, int y, int *r, int *g, int *b) {
    const uint8_t *p = g_mem + ((size_t)y * W + x) * 4;
    *r = p[0]; *g = p[1]; *b = p[2];
}
static int near(int a, int b, int tol) { return a >= b - tol && a <= b + tol; }
static int is(int x, int y, int r, int g, int b, int tol) {
    int pr, pg, pb;
    px(x, y, &pr, &pg, &pb);
    if (near(pr, r, tol) && near(pg, g, tol) && near(pb, b, tol)) return 1;
    printf("    pixel (%d,%d) is %d,%d,%d, wanted %d,%d,%d\n", x, y, pr, pg, pb, r, g, b);
    return 0;
}
static void swap_frame(EGLDisplay d, EGLSurface s) { memset(g_mem, 0x55, sizeof g_mem); (void)d; (void)s; }

/* GL names the test uses */
#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_DEPTH_BUFFER_BIT 0x100
#define GL_TEXTURE_2D 0x0DE1
#define GL_UNSIGNED_BYTE 0x1401
#define GL_UNSIGNED_SHORT 0x1403
#define GL_FLOAT 0x1406
#define GL_FIXED 0x140C
#define GL_RGBA 0x1908
#define GL_RGB 0x1907
#define GL_ALPHA 0x1906
#define GL_TRIANGLES 4
#define GL_TRIANGLE_STRIP 5
#define GL_TEXTURE_ENV 0x2300
#define GL_TEXTURE_ENV_MODE 0x2200
#define GL_REPLACE 0x1E01
#define GL_MODULATE 0x2100
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_NEAREST 0x2600
#define GL_LINEAR 0x2601
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_TEXTURE_CROP_RECT_OES 0x8B9D
#define GL_MODELVIEW 0x1700
#define GL_PROJECTION 0x1701
#define GL_VERTEX_ARRAY 0x8074
#define GL_NORMAL_ARRAY 0x8075
#define GL_COLOR_ARRAY 0x8076
#define GL_TEXTURE_COORD_ARRAY 0x8078
#define GL_BLEND 0x0BE2
#define GL_SRC_ALPHA 0x0302
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
#define GL_ONE 1
#define GL_LIGHTING 0x0B50
#define GL_LIGHT0 0x4000
#define GL_POSITION 0x1203
#define GL_DIFFUSE 0x1201
#define GL_AMBIENT 0x1200
#define GL_FOG 0x0B60
#define GL_FOG_MODE 0x0B65
#define GL_FOG_START 0x0B63
#define GL_FOG_END 0x0B64
#define GL_FOG_COLOR 0x0B66
#define GL_LINEAR_FOG 0x2601
#define GL_ALPHA_TEST 0x0BC0
#define GL_GREATER 0x204
#define GL_SCISSOR_TEST 0x0C11
#define GL_DITHER 0x0BD0
#define GL_FLAT 0x1D00
#define GL_ARRAY_BUFFER 0x8892
#define GL_STATIC_DRAW 0x88E4
#define GL_TEXTURE0 0x84C0
#define GL_TEXTURE1 0x84C1
#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_CULL_FACE 0x0B44
#define GL_DEPTH_TEST 0x0B71
#define GL_COLOR_MATERIAL 0x0B57
#define GL_MODELVIEW_MATRIX 0x0BA6
#define GL_VERSION 0x1F02
#define GL_EXTENSIONS 0x1F03
#define GL_ADD 0x0104
#define GL_DECAL 0x2101

/* a w x h RGBA texture: rows [0, h/2) one colour, the rest another (the first row uploaded is the top of a PNG) */
static void make_tex(int w, int h, const uint8_t *top, const uint8_t *bottom, uint8_t *out) {
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) memcpy(out + ((size_t)y * w + x) * 4, y < h / 2 ? top : bottom, 4);
}

static void scene_clear(EGLDisplay d, EGLSurface s) {
    printf("clear\n");
    glClearColor(1.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(5, 5, 255, 0, 0, 1) && is(W - 5, H - 5, 255, 0, 0, 1));
}

/* BootAnimation::movie() / android(): a texture drawn with glDrawTexiOES, REPLACE, the crop rectangle flipping it */
static void scene_drawtex(EGLDisplay d, EGLSurface s) {
    printf("glDrawTexiOES with a flipped crop rectangle (the boot animation)\n");
    static uint8_t t[64 * 64 * 4];
    const uint8_t red[4] = { 255, 0, 0, 255 }, blue[4] = { 0, 0, 255, 255 };
    make_tex(64, 64, red, blue, t);
    GLuint tex = 0;
    glShadeModel(GL_FLAT); glDisable(GL_DITHER); glDisable(GL_SCISSOR_TEST);
    glClearColor(0.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, t);
    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    GLint crop[4] = { 0, 64, 64, -64 };
    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
    glDrawTexiOES(100, 50, 0, 128, 128);
    swap_frame(d, s); eglSwapBuffers(d, s);
    /* the rectangle is x 100..228, y (up from the bottom) 50..178: rows H-178..H-50 from the top; red on top */
    CHECK(is(160, H - 178 + 10, 255, 0, 0, 1));            /* near its top: the top of the picture */
    CHECK(is(160, H - 50 - 10, 0, 0, 255, 1));             /* near its bottom */
    CHECK(is(50, H - 100, 0, 0, 0, 1) && is(260, H - 100, 0, 0, 0, 1));   /* outside: the clear colour */
    /* without the flip the picture is upside down */
    GLint plain[4] = { 0, 0, 64, 64 };
    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, plain);
    glClear(GL_COLOR_BUFFER_BIT);
    glDrawTexiOES(100, 50, 0, 128, 128);
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(160, H - 178 + 10, 0, 0, 255, 1) && is(160, H - 50 - 10, 255, 0, 0, 1));
    /* android(): the mask is blended over the clear colour */
    static uint8_t half[64 * 64 * 4];
    const uint8_t clear[4] = { 255, 255, 255, 0 }, solid[4] = { 0, 255, 0, 255 };
    make_tex(64, 64, clear, solid, half);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, half);
    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
    glClearColor(0.f, 0.f, 1.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDrawTexiOES(0, 0, 0, 128, 128);
    glDisable(GL_BLEND);
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(60, H - 30, 0, 255, 0, 1));                  /* the picture's lower half is solid green, at the bottom of the rectangle */
    CHECK(is(60, H - 100, 0, 0, 255, 1));                 /* its upper half is transparent: the blue clear colour shows */
    glDeleteTextures(1, &tex);
}

/* old games: matrices, client arrays, vertex colours */
static void scene_triangles(EGLDisplay d, EGLSurface s) {
    printf("client arrays, matrices, vertex colours\n");
    glDisable(GL_TEXTURE_2D);
    glViewport(0, 0, W, H);
    glClearColor(0.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
    glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrthof(0.f, (float)W, 0.f, (float)H, -1.f, 1.f);
    glMatrixMode(GL_MODELVIEW); glLoadIdentity();
    static const float quad[] = { 0, 0, 100, 0, 0, 100, 100, 100 };
    static const uint8_t col[] = { 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255 };
    glEnableClientState(GL_VERTEX_ARRAY);
    glEnableClientState(GL_COLOR_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, quad);
    glColorPointer(4, GL_UNSIGNED_BYTE, 0, col);
    glPushMatrix();
    glTranslatef(50.f, 40.f, 0.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glPopMatrix();
    /* rotated 90 degrees about the origin: the same square, x -100..0 (+ translation) */
    glPushMatrix();
    glTranslatef(200.f, 40.f, 0.f);
    glRotatef(90.f, 0.f, 0.f, 1.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glPopMatrix();
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(100, H - 90, 0, 255, 0, 1));                  /* the first square: x 50..150, y 40..140 */
    CHECK(is(60, H - 50, 0, 255, 0, 1) && is(145, H - 135, 0, 255, 0, 1));
    CHECK(is(30, H - 90, 0, 0, 0, 1));
    CHECK(is(160, H - 90, 0, 255, 0, 1));                  /* the second: x 100..200, y 40..140 (rotated) overlaps the first */
    CHECK(is(180, H - 90, 0, 255, 0, 1));
    CHECK(is(210, H - 90, 0, 0, 0, 1));
    /* indices, in a buffer and in memory; a colour from glColor4f when the array is off */
    glDisableClientState(GL_COLOR_ARRAY);
    glColor4f(1.f, 0.f, 1.f, 1.f);
    static const float tri[] = { 0, 0, 60, 0, 0, 60, 60, 60 };
    static const uint16_t idx[] = { 0, 1, 2, 2, 1, 3 };
    glClear(GL_COLOR_BUFFER_BIT);
    glVertexPointer(2, GL_FLOAT, 0, tri);
    glPushMatrix(); glTranslatef(10.f, 10.f, 0.f);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, idx);
    glPopMatrix();
    GLuint vb = 0;
    glGenBuffers(1, &vb);
    glBindBuffer(GL_ARRAY_BUFFER, vb);
    glBufferData(GL_ARRAY_BUFFER, sizeof tri, tri, GL_STATIC_DRAW);
    glVertexPointer(2, GL_FLOAT, 0, (const void *)0);
    glColor4f(1.f, 1.f, 0.f, 1.f);
    glPushMatrix(); glTranslatef(100.f, 10.f, 0.f);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, idx);
    glPopMatrix();
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(30, H - 30, 255, 0, 255, 1) && is(120, H - 30, 255, 255, 0, 1) && is(85, H - 30, 0, 0, 0, 1));
    /* GL_FIXED vertices (16.16) */
    glClear(GL_COLOR_BUFFER_BIT);
    static const int fx[] = { 0, 0, 80 << 16, 0, 0, 80 << 16, 80 << 16, 80 << 16 };
    glColor4f(0.f, 1.f, 1.f, 1.f);
    glVertexPointer(2, GL_FIXED, 0, fx);
    glPushMatrix(); glTranslatef(20.f, 20.f, 0.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glPopMatrix();
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(60, H - 60, 0, 255, 255, 1) && is(110, H - 60, 0, 0, 0, 1));
    glDisableClientState(GL_VERTEX_ARRAY);
}

static void scene_texture_modes(EGLDisplay d, EGLSurface s) {
    printf("textured geometry: MODULATE, DECAL, ADD, an ALPHA texture\n");
    GLuint tex[2];
    glGenTextures(2, tex);
    uint8_t white[4 * 4], grey[4 * 4];
    for (int i = 0; i < 4; i++) { memcpy(white + i * 4, (uint8_t[]){ 255, 255, 255, 255 }, 4); memcpy(grey + i * 4, (uint8_t[]){ 128, 128, 128, 255 }, 4); }
    glBindTexture(GL_TEXTURE_2D, tex[0]);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, grey);
    glBindTexture(GL_TEXTURE_2D, tex[1]);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    static const uint8_t a8[4] = { 128, 128, 128, 128 };
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, 2, 2, 0, GL_ALPHA, GL_UNSIGNED_BYTE, a8);
    glViewport(0, 0, W, H);
    glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrthof(0.f, (float)W, 0.f, (float)H, -1.f, 1.f);
    glMatrixMode(GL_MODELVIEW); glLoadIdentity();
    static const float v[] = { 0, 0, 50, 0, 0, 50, 50, 50 };
    static const float tc[] = { 0, 0, 1, 0, 0, 1, 1, 1 };
    glEnableClientState(GL_VERTEX_ARRAY); glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, v); glTexCoordPointer(2, GL_FLOAT, 0, tc);
    glEnable(GL_TEXTURE_2D);
    glClearColor(0.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
    glBindTexture(GL_TEXTURE_2D, tex[0]);
    glColor4f(1.f, 0.f, 0.f, 1.f);
    glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);           /* red * grey */
    glPushMatrix(); glTranslatef(10.f, 10.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);           /* grey */
    glPushMatrix(); glTranslatef(70.f, 10.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_ADD);                /* red + grey */
    glPushMatrix(); glTranslatef(130.f, 10.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glBindTexture(GL_TEXTURE_2D, tex[1]);                                  /* ALPHA 128: colour from the vertex, alpha from the texture */
    glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
    glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glColor4f(1.f, 1.f, 1.f, 1.f);
    glPushMatrix(); glTranslatef(190.f, 10.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glDisable(GL_BLEND);
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(35, H - 35, 128, 0, 0, 2));
    CHECK(is(95, H - 35, 128, 128, 128, 2));
    CHECK(is(155, H - 35, 255, 128, 128, 2));
    CHECK(is(215, H - 35, 128, 128, 128, 3));              /* white at alpha .5 over black */
    glDisableClientState(GL_TEXTURE_COORD_ARRAY); glDisableClientState(GL_VERTEX_ARRAY);
    glDeleteTextures(2, tex);
}

static void scene_lighting_fog_alpha(EGLDisplay d, EGLSurface s) {
    printf("lighting, fog, the alpha test\n");
    glDisable(GL_TEXTURE_2D);
    glViewport(0, 0, W, H);
    glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrthof(0.f, (float)W, 0.f, (float)H, -100.f, 100.f);
    glMatrixMode(GL_MODELVIEW); glLoadIdentity();
    static const float v[] = { 0, 0, 0, 80, 0, 0, 0, 80, 0, 80, 80, 0 };
    static const float n[] = { 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1 };
    glEnableClientState(GL_VERTEX_ARRAY); glEnableClientState(GL_NORMAL_ARRAY);
    glVertexPointer(3, GL_FLOAT, 0, v); glNormalPointer(GL_FLOAT, 0, n);
    glClearColor(0.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
    /* one light straight on the square: ambient 0.2*0.2 from the model + 0.8 diffuse */
    glEnable(GL_LIGHTING); glEnable(GL_LIGHT0);
    static const float pos[4] = { 0.f, 0.f, 1.f, 0.f };
    glLightfv(GL_LIGHT0, GL_POSITION, pos);
    glColor4f(1.f, 1.f, 1.f, 1.f);
    glPushMatrix(); glTranslatef(10.f, 10.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    /* the light behind it: only the ambient terms remain */
    static const float behind[4] = { 0.f, 0.f, -1.f, 0.f };
    glLightfv(GL_LIGHT0, GL_POSITION, behind);
    glPushMatrix(); glTranslatef(110.f, 10.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    /* colour material: the vertex colour is the material */
    glLightfv(GL_LIGHT0, GL_POSITION, pos);
    glEnable(GL_COLOR_MATERIAL); glColor4f(1.f, 0.f, 0.f, 1.f);
    glPushMatrix(); glTranslatef(210.f, 10.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glDisable(GL_COLOR_MATERIAL); glDisable(GL_LIGHTING);
    glDisableClientState(GL_NORMAL_ARRAY);
    /* linear fog: halfway between start and end the colour is the mean of the fog colour and the fragment's */
    glEnable(GL_FOG);
    glFogx(GL_FOG_MODE, GL_LINEAR_FOG);
    glFogf(GL_FOG_START, 0.f); glFogf(GL_FOG_END, 100.f);
    static const float fc[4] = { 0.f, 0.f, 1.f, 1.f };
    glFogfv(GL_FOG_COLOR, fc);
    glColor4f(1.f, 0.f, 0.f, 1.f);
    glPushMatrix(); glTranslatef(10.f, 110.f, -50.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glDisable(GL_FOG);
    /* alpha test: alpha 0.3 fails GREATER 0.5, alpha 0.8 passes */
    glEnable(GL_ALPHA_TEST); glAlphaFunc(GL_GREATER, 0.5f);
    glColor4f(1.f, 1.f, 1.f, 0.3f);
    glPushMatrix(); glTranslatef(110.f, 110.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glColor4f(1.f, 1.f, 1.f, 0.8f);
    glPushMatrix(); glTranslatef(210.f, 110.f, 0.f); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glPopMatrix();
    glDisable(GL_ALPHA_TEST);
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(50, H - 50, 214, 214, 214, 3));               /* 0.04 + 0.8 -> 0.84 */
    CHECK(is(150, H - 50, 10, 10, 10, 3));                 /* 0.04 */
    CHECK(is(250, H - 50, 255, 0, 0, 2));                  /* red material: 0.2 + 1.0 red, clamped */
    CHECK(is(50, H - 150, 128, 0, 128, 3));                /* fog halfway */
    CHECK(is(150, H - 150, 0, 0, 0, 1));                   /* discarded */
    CHECK(is(250, H - 150, 255, 255, 255, 1));
    glDisableClientState(GL_VERTEX_ARRAY);
}

static void scene_two_units(EGLDisplay d, EGLSurface s) {
    printf("two texture units, a matrix on the texture, glGet*\n");
    GLuint tex[2];
    glGenTextures(2, tex);
    static const uint8_t a[4] = { 255, 0, 0, 255 }, b[4] = { 128, 128, 128, 255 };
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex[0]);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, a);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, tex[1]);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    glEnable(GL_TEXTURE_2D);
    glActiveTexture(GL_TEXTURE0); glEnable(GL_TEXTURE_2D);
    glViewport(0, 0, W, H);
    glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrthof(0.f, (float)W, 0.f, (float)H, -1.f, 1.f);
    glMatrixMode(GL_MODELVIEW); glLoadIdentity();
    glClearColor(0.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
    static const float v[] = { 20, 20, 120, 20, 20, 120, 120, 120 };
    static const float tc[] = { 0, 0, 1, 0, 0, 1, 1, 1 };
    glEnableClientState(GL_VERTEX_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, v);
    glClientActiveTexture(GL_TEXTURE0); glEnableClientState(GL_TEXTURE_COORD_ARRAY); glTexCoordPointer(2, GL_FLOAT, 0, tc);
    glClientActiveTexture(GL_TEXTURE1); glEnableClientState(GL_TEXTURE_COORD_ARRAY); glTexCoordPointer(2, GL_FLOAT, 0, tc);
    glColor4f(1.f, 1.f, 1.f, 1.f);
    glActiveTexture(GL_TEXTURE0); glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
    glActiveTexture(GL_TEXTURE1); glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
    glMatrixMode(0x1702 /* GL_TEXTURE */); glLoadIdentity(); glTranslatef(0.5f, 0.f, 0.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    swap_frame(d, s); eglSwapBuffers(d, s);
    CHECK(is(70, H - 70, 128, 0, 0, 2));                   /* red * grey (the 1x1 textures repeat or clamp: the same texel) */
    float m[16];
    glMatrixMode(GL_MODELVIEW); glLoadIdentity(); glTranslatef(3.f, 4.f, 5.f);
    glGetFloatv(GL_MODELVIEW_MATRIX, m);
    CHECK(m[12] == 3.f && m[13] == 4.f && m[14] == 5.f && m[0] == 1.f);
    const GLubyte *ver = glGetString(GL_VERSION), *ext = glGetString(GL_EXTENSIONS);
    CHECK(ver && strstr((const char *)ver, "ES-CM 1.1"));
    CHECK(ext && strstr((const char *)ext, "GL_OES_draw_texture"));
    glClientActiveTexture(GL_TEXTURE1); glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glClientActiveTexture(GL_TEXTURE0); glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glDisableClientState(GL_VERTEX_ARRAY);
    glActiveTexture(GL_TEXTURE1); glDisable(GL_TEXTURE_2D); glActiveTexture(GL_TEXTURE0);
    glDeleteTextures(2, tex);
}


/* ---- window buffers of every format the bridge knows, with a row pitch longer than the width ---- */
static void rdpx(int fmt, int stride, int x, int y, int *r, int *g, int *b, int *a) {
    const uint8_t *p;
    *a = 255;
    switch (fmt) {
    case 1: case 2: p = g_mem + ((size_t)y * stride + x) * 4; *r = p[0]; *g = p[1]; *b = p[2]; *a = p[3]; break;
    case 5: p = g_mem + ((size_t)y * stride + x) * 4; *r = p[2]; *g = p[1]; *b = p[0]; *a = p[3]; break;
    case 3: p = g_mem + ((size_t)y * stride + x) * 3; *r = p[0]; *g = p[1]; *b = p[2]; break;
    default: { uint16_t v; memcpy(&v, g_mem + ((size_t)y * stride + x) * 2, 2);
               *r = (v >> 11) << 3 | (v >> 13); *g = (v >> 5 & 63) << 2 | (v >> 9 & 3); *b = (v & 31) << 3 | (v >> 2 & 7); }
    }
}
static int isf(int fmt, int stride, int x, int y, int r, int g, int b, int tol) {
    int pr, pg, pb, pa;
    rdpx(fmt, stride, x, y, &pr, &pg, &pb, &pa);
    if (near(pr, r, tol) && near(pg, g, tol) && near(pb, b, tol)) return 1;
    printf("    format %d stride %d pixel (%d,%d) is %d,%d,%d, wanted %d,%d,%d\n", fmt, stride, x, y, pr, pg, pb, r, g, b);
    return 0;
}
static void scene_formats(EGLDisplay d, EGLSurface s, const char *how) {
    printf("window buffers of every format (%s)\n", how);
    static const struct { int format, usage, stride; } cases[] = {
        { 2, 0x933, W }, { 2, 0x933, W + 8 }, { 1, 0x933, W }, { 1, 0x933, W + 32 }, { 5, 0x933, W }, { 3, 0x933, W }, { 3, 0x933, W + 3 },
        { 4, 0x933, W }, { 4, 0x933, W + 5 },
    };
    for (unsigned i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        int f = cases[i].format, st = cases[i].stride;
        g_buf.format = f; g_buf.usage_deprecated = cases[i].usage; g_buf.stride = st;
        memset(g_mem, 0x55, sizeof g_mem);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(1.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);          /* red, and blue in the lower half of the image */
        glEnable(GL_SCISSOR_TEST); glScissor(0, 0, W, H / 2);
        glClearColor(0.f, 0.f, 1.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        eglSwapBuffers(d, s);
        int tol = f == 4 ? 8 : 1;
        CHECK(isf(f, st, 3, 3, 255, 0, 0, tol) && isf(f, st, W - 4, H / 2 - 4, 255, 0, 0, tol));       /* top of the image: red */
        CHECK(isf(f, st, 3, H / 2 + 3, 0, 0, 255, tol) && isf(f, st, W - 4, H - 4, 0, 0, 255, tol));   /* bottom: blue (the image is not upside down) */
        if (f == 1 || f == 5) { int r, g, b, a; rdpx(f, st, 10, 10, &r, &g, &b, &a); CHECK(a == 255); }
        CHECK(f == 4 || f == 3 || g_mem[(size_t)(H - 1) * st * (f == 4 ? 2 : 4) + 4 * (W - 1) + 0] != 0x55);     /* the last pixel was written */
    }
    g_buf.format = 2; g_buf.usage_deprecated = 0x933; g_buf.stride = W;
}

/* ---- a window buffer of the guest as a texture (a compositor's layer): any format, any row pitch ---- */
static void scene_buffer_textures(EGLDisplay d, EGLSurface s, EGLConfig cfg, const char *how) {
    printf("window buffers as textures (%s)\n", how);
    const EGLint c2[] = { 0x3098, 2, EGL_NONE };
    EGLContext x = eglCreateContext(d, cfg, 0, c2);
    CHECK(x != 0 && eglMakeCurrent(d, s, s, x));
    GLuint vs = glCreateShader(0x8B31), fs = glCreateShader(0x8B30), pr = glCreateProgram();
    const char *vsrc = "attribute vec2 p; varying vec2 t; void main() { t = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }";
    const char *fsrc = "precision mediump float; uniform sampler2D s; varying vec2 t; void main() { gl_FragColor = texture2D(s, t); }";
    glShaderSource(vs, 1, &vsrc, 0); glCompileShader(vs); glShaderSource(fs, 1, &fsrc, 0); glCompileShader(fs);
    glAttachShader(pr, vs); glAttachShader(pr, fs); glBindAttribLocation(pr, 0, "p"); glLinkProgram(pr);
    GLint ok = 0; glGetProgramiv(pr, 0x8B82, &ok);
    CHECK(ok);
    glUseProgram(pr);
    static const float quad[] = { -1, -1, 1, -1, -1, 1, 1, 1 };
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, 0, 0, quad);
    static const struct { int format, stride, w, h; } bufs[] = { { 2, 80, 64, 32 }, { 1, 64, 64, 32 }, { 4, 72, 64, 32 }, { 4, 64, 64, 32 }, { 3, 70, 64, 32 } };
    for (unsigned i = 0; i < sizeof bufs / sizeof bufs[0]; i++) {
        int f = bufs[i].format, st = bufs[i].stride, bw = bufs[i].w, bh = bufs[i].h;
        static uint8_t mem[96 * 32 * 4];
        memset(mem, 0x77, sizeof mem);
        /* left half red, right half green; the texture's first row is the top row of the buffer */
        for (int y = 0; y < bh; y++) for (int xx = 0; xx < bw; xx++) {
            int red = xx < bw / 2;
            if (f == 1 || f == 2) { uint8_t *p = mem + ((size_t)y * st + xx) * 4; p[0] = red ? 255 : 0; p[1] = red ? 0 : 255; p[2] = 0; p[3] = 255; }
            else if (f == 3) { uint8_t *p = mem + ((size_t)y * st + xx) * 3; p[0] = red ? 255 : 0; p[1] = red ? 0 : 255; p[2] = 0; }
            else { uint16_t v = red ? (31 << 11) : (63 << 5); memcpy(mem + ((size_t)y * st + xx) * 2, &v, 2); }
        }
        struct ANativeWindowBuffer nb;
        memset(&nb, 0, sizeof nb);
        nb.common.incRef = inc; nb.common.decRef = dec;
        nb.width = bw; nb.height = bh; nb.stride = st; nb.format = f; nb.usage_deprecated = 0x933; nb.handle = mem;
        EGLImageKHR img = eglCreateImageKHR(d, x, 0x3140, &nb, 0);
        CHECK(img != 0);
        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, img);
        g_buf.format = 2; g_buf.usage_deprecated = 0x933; g_buf.stride = W;
        glClearColor(0.f, 0.f, 0.f, 1.f); glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        eglSwapBuffers(d, s);
        /* t = 0 is the first row uploaded, at the bottom of the quad: the image on the screen is upside down; left and right are what matter */
        int tol = f == 4 ? 8 : 1;
        CHECK(isf(2, W, 40, 60, 255, 0, 0, tol) && isf(2, W, W - 40, H - 60, 0, 255, 0, tol));
        if (f != 4) CHECK(isf(2, W, W / 2 - 20, 100, 255, 0, 0, tol) && isf(2, W, W / 2 + 20, 100, 0, 255, 0, tol));
        glDeleteTextures(1, &tex);
        eglDestroyImageKHR(d, img);
    }
    glDisableVertexAttribArray(0);
    eglMakeCurrent(d, 0, 0, 0);
    eglDestroyContext(d, x);
}

/* every program the generator can ask for must link: a stray character in the GLSL costs a whole scene */
static void scene_all_programs(void) {
    printf("every program key the generator can make\n");
    struct g1 *st = G1();
    int built = 0, fail = 0;
    uint32_t rnd = 12345;
    for (int i = 0; i < 200; i++) {
        rnd = rnd * 1664525u + 1013904223u;
        uint64_t key = 0;
        uint32_t r = rnd;
        for (int u = 0; u < G1_NTEX; u++) {
            if (r & 1) { key |= K_TEX(u) | K_MODE(u, (r >> 1) % 5); if (r & 8) key |= K_ALPHA(u); if (r & 16) key |= K_TCARR(u); }
            r = r * 5 + 7;
        }
        if (r & 1) key |= K_COLARR;
        if (r & 2) key |= K_ATEST | K_AFUNC((r >> 2) % 8);
        if (r & 32) key |= K_FOG | K_FOGMODE((r >> 6) % 3);
        if (r & 256) { key |= K_LIGHT | K_LMASK((r >> 9) & 0xff); if (r & 1024) key |= K_CMAT; if (r & 2048) key |= K_NRMARR; }
        if ((r & 4096) && (key & K_TEX(0))) key |= K_PSPRITE;
        if (i % 7 == 0) key = (key & (K_TEX(0) | K_TEX(1) | K_MODE(0, 7) | K_MODE(1, 7) | K_ALPHA(0) | K_ALPHA(1))) | K_DRAWTEX;
        struct g1_prog p;
        g1_build(st, &p, key);
        built++;
        if (!p.ok) { fail++; printf("    key %llx did not link\n", (unsigned long long)key); }
        else e_glDeleteProgram(p.prog);
    }
    CHECK(fail == 0 && g_link_fail == 0);
    printf("    %d programs, %d failed\n", built, fail);
}

int main(void) {
    int tries = 0;
    setvbuf(stdout, 0, _IOLBF, 0);
    for (; tries < 50; tries++) { struct ts *T = ts_get(); if (T) break; usleep(100000); }
    if (tries == 50) { printf("no host at %s: start hostmain first (see run-desktop.sh)\n", AEMU_GL_SOCKET); return 2; }
    memset(&g_buf, 0, sizeof g_buf);
    g_buf.common.incRef = inc; g_buf.common.decRef = dec;
    g_buf.width = W; g_buf.height = H; g_buf.stride = W; g_buf.format = 2; g_buf.usage_deprecated = 0x933; g_buf.handle = g_mem;
    g_win.common.incRef = inc; g_win.common.decRef = dec;
    g_win.dequeueBuffer = fw_dequeue; g_win.queueBuffer = fw_queue; g_win.query = fw_query; g_win.perform = fw_perform; g_win.setSwapInterval = fw_interval; g_win.cancelBuffer = fw_cancel;

    CHECK(solo());
    EGLDisplay d = eglGetDisplay(0);
    CHECK(eglInitialize(d, 0, 0));
    /* BootAnimation::readyToRun() of 4.4 */
    const EGLint attribs[] = { 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3025, 0, EGL_NONE };
    EGLConfig cfg; EGLint n = 0;
    CHECK(eglChooseConfig(d, attribs, &cfg, 1, &n) && n == 1);
    EGLint format = 0;
    CHECK(eglGetConfigAttrib(d, cfg, 0x302E, &format) && format == 2);
    EGLSurface s = eglCreateWindowSurface(d, cfg, &g_win, 0);
    CHECK(s != 0);
    EGLContext c = eglCreateContext(d, cfg, 0, 0);                 /* no attributes: GLES 1 */
    CHECK(c != 0);
    CHECK(eglMakeCurrent(d, s, s, c));
    EGLint w = 0, h = 0;
    eglQuerySurface(d, s, EGL_WIDTH, &w); eglQuerySurface(d, s, EGL_HEIGHT, &h);
    CHECK(w == W && h == H);

    scene_clear(d, s);
    scene_drawtex(d, s);
    scene_triangles(d, s);
    scene_texture_modes(d, s);
    scene_lighting_fog_alpha(d, s);
    scene_two_units(d, s);
    scene_all_programs();
    CHECK(g_missing == 0);
    scene_formats(d, s, "converted by the host");
    CHECK(eglMakeCurrent(d, 0, 0, 0));
    scene_buffer_textures(d, s, cfg, "uploaded as the buffer holds them");
    CHECK(eglMakeCurrent(d, s, s, c));
    /* a host that does not know the newer calls: what the guest did before */
    ts_get()->caps = 0;
    scene_formats(d, s, "converted by the guest");
    CHECK(eglMakeCurrent(d, 0, 0, 0));
    scene_buffer_textures(d, s, cfg, "repacked by the guest");
    CHECK(eglMakeCurrent(d, s, s, c));

    CHECK(eglMakeCurrent(d, 0, 0, 0));
    eglDestroyContext(d, c);
    eglDestroySurface(d, s);
    printf(bad ? "%d of %d check(s) FAILED\n" : "gles1 end to end: all %d checks passed\n", bad ? bad : checks, checks);
    return bad != 0;
}
