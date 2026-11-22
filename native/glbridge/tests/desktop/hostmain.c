/* Desktop host for the bridge test: host.c as it is, on Mesa's llvmpipe (surfaceless EGL). */
#include "hostshim.h"
#include "host.c"

/* Mesa's libEGL / libGLESv2 hand these out through eglGetProcAddress only; Android's export them */
#define FWD(ret, name, params, args) ret name params { static ret (*fp_) params; if (!fp_) fp_ = (void *)eglGetProcAddress(#name); return fp_ ? fp_ args : (ret)0; }
FWD(EGLImageKHR, eglCreateImageKHR, (EGLDisplay d, EGLContext c, EGLenum t, EGLClientBuffer b, const EGLint *a), (d, c, t, b, a))
FWD(EGLBoolean, eglDestroyImageKHR, (EGLDisplay d, EGLImageKHR i), (d, i))
FWD(EGLSyncKHR, eglCreateSyncKHR, (EGLDisplay d, EGLenum t, const EGLint *a), (d, t, a))
FWD(EGLBoolean, eglDestroySyncKHR, (EGLDisplay d, EGLSyncKHR s), (d, s))
FWD(EGLint, eglClientWaitSyncKHR, (EGLDisplay d, EGLSyncKHR s, EGLint f, EGLTimeKHR t), (d, s, f, t))
void glEGLImageTargetTexture2DOES(GLenum t, GLeglImageOES i) { static void (*f)(GLenum, GLeglImageOES); if (!f) f = (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES"); if (f) f(t, i); }
EGLClientBuffer eglGetNativeClientBufferANDROID(const struct AHardwareBuffer *b) { (void)b; return 0; }
#include <signal.h>
static jstring fake_new(JNIEnv *e, const char *s) { (void)e; if (strlen(s) < 200) fprintf(stderr, "[host] start: %s\n", s); return (jstring)strdup(s); }
static JNIEnv *g_env;
/* RING_DUMP_FILE set: the call ring is on, and SIGTERM writes it out as the app's log export would */
static void dump_ring(int sig) {
    (void)sig;
    const char *f = getenv("RING_DUMP_FILE");
    jstring t = Java_app_aemu_core_GlHost_nativeTraceDump(g_env, 0);
    FILE *o = f ? fopen(f, "w") : 0;
    if (o) { fputs((const char *)t, o); fclose(o); }
    _exit(0);
}
static const char *fake_get(JNIEnv *e, jstring s, jboolean *c) { (void)e; (void)c; return (const char *)s; }
static void fake_rel(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; (void)c; }
int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "/dev/socket/aemu_gl";
    PFNEGLGETPLATFORMDISPLAYEXTPROC gpd = (void *)eglGetProcAddress("eglGetPlatformDisplayEXT");
    g_dpy = gpd(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, 0);
    EGLint n = 0;
    if (!eglInitialize(g_dpy, 0, 0)) { fprintf(stderr, "no egl\n"); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    static const EGLint want[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    if (!eglChooseConfig(g_dpy, want, &g_cfg, 1, &n) || n < 1) { fprintf(stderr, "no config\n"); return 1; }
    struct JNINativeInterface_ tbl; memset(&tbl, 0, sizeof tbl);
    tbl.NewStringUTF = (void *)fake_new; tbl.GetStringUTFChars = (void *)fake_get; tbl.ReleaseStringUTFChars = (void *)fake_rel;
    const struct JNINativeInterface_ *envp = &tbl;
    g_env = (JNIEnv *)&envp;
    if (getenv("RING_DUMP_FILE")) { Java_app_aemu_core_GlHost_nativeTrace(g_env, 0, 1); signal(SIGTERM, dump_ring); }
    /* AEMU_TEST_VENDOR / AEMU_TEST_RENDERER: the SoC / GPU spoofer's strings (GlHost.identity) */
    if (getenv("AEMU_TEST_VENDOR") || getenv("AEMU_TEST_RENDERER"))
        Java_app_aemu_core_GlHost_nativeIdentity((JNIEnv *)&envp, 0, (jstring)getenv("AEMU_TEST_VENDOR"), (jstring)getenv("AEMU_TEST_RENDERER"));
    Java_app_aemu_core_GlHost_nativeStart((JNIEnv *)&envp, 0, (jstring)path);
    signal(SIGPIPE, SIG_IGN);
    for (;;) pause();
}
