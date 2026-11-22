#pragma once
#include <jni.h>
typedef struct ANativeWindow ANativeWindow;
static inline ANativeWindow *ANativeWindow_fromSurface(JNIEnv *e, jobject s) { (void)e; (void)s; return 0; }
static inline void ANativeWindow_release(ANativeWindow *w) { (void)w; }
