#pragma once
#include <stdio.h>
#include <stdarg.h>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); fprintf(stderr, "[host %s] ", tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr); va_end(ap); return 0;
}
