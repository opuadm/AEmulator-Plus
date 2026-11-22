/* emulated TLS on pthread keys, as the NDK's runtime does for API < 29 (zig's compiler-rt has no __emutls_get_address) */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
struct ctl { unsigned long size, align, index; void *templ; };
struct arr { unsigned long n; void *p[1]; };
static pthread_once_t once = PTHREAD_ONCE_INIT;
static pthread_key_t key;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static unsigned long counter;
static void mk(void) { pthread_key_create(&key, 0); }
void *__emutls_get_address(struct ctl *c) {
    pthread_once(&once, mk);
    pthread_mutex_lock(&mu);
    if (!c->index) c->index = ++counter;
    unsigned long idx = c->index;
    pthread_mutex_unlock(&mu);
    struct arr *a = pthread_getspecific(key);
    if (!a || a->n < idx) {
        unsigned long n = idx + 8;
        struct arr *b = calloc(1, sizeof(struct arr) + n * sizeof(void *));
        if (!b) abort();
        b->n = n;
        if (a) { memcpy(b->p, a->p, a->n * sizeof(void *)); free(a); }
        pthread_setspecific(key, b);
        a = b;
    }
    if (!a->p[idx - 1]) {
        void *p = calloc(1, c->size ? c->size : 1);
        if (!p) abort();
        if (c->templ) memcpy(p, c->templ, c->size);
        a->p[idx - 1] = p;
    }
    return a->p[idx - 1];
}
