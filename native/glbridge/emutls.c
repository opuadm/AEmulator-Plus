/* Emulated TLS on pthread keys, as the NDK's runtime does for API < 29 (zig's compiler-rt has no __emutls_get_address); built into the
 * x86 / x86-64 bridge by build-x86-zig.sh. Every GL call of the guest reads two thread-locals (the call table, the stream), so this is
 * on the path of every call, and the guest's CPU is a translator: the lookup must not take a lock. Once a variable has its index and the
 * thread its array, a lookup is one atomic load, pthread_getspecific and two compares; locks are for the first use of a variable and of
 * a thread only. */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
struct ctl { unsigned long size, align, index; void *templ; };
struct arr { unsigned long n; void *p[1]; };
static pthread_once_t once = PTHREAD_ONCE_INIT;
static pthread_key_t key;
static volatile int key_ready;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static unsigned long counter;
static void mk(void) { pthread_key_create(&key, 0); __atomic_store_n(&key_ready, 1, __ATOMIC_RELEASE); }
static void *slow(struct ctl *c) {
    pthread_once(&once, mk);
    pthread_mutex_lock(&mu);
    unsigned long idx = c->index;
    if (!idx) { idx = ++counter; __atomic_store_n(&c->index, idx, __ATOMIC_RELEASE); }
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
void *__emutls_get_address(struct ctl *c) {
    unsigned long idx = __atomic_load_n(&c->index, __ATOMIC_ACQUIRE);
    if (idx && __atomic_load_n(&key_ready, __ATOMIC_ACQUIRE)) {
        struct arr *a = pthread_getspecific(key);
        if (a && a->n >= idx && a->p[idx - 1]) return a->p[idx - 1];
    }
    return slow(c);
}
