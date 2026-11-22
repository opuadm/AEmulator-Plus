/*
 * glGetString with no context current, end to end: guest.c -> the unix socket -> host.c on Mesa's llvmpipe.
 *   sh run-strings.sh
 * What SPen (S Note, Quick Memo, Flash Annotate) does after a refused eglCreateContext: reads GL_RENDERER from no context and
 * hands it to strncmp. The guest must get a string, and it must be the spoofer's (AEMU_TEST_*) when the VM names a GPU, the
 * phone's own (what a real context says) when it does not; never NULL, never a made-up one.
 */
#include <stdarg.h>
#include <stdio.h>
#include "../../guest.c"

int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }
int __system_property_get(const char *name, char *value) { (void)name; value[0] = 0; return 0; }

static int bad, checks;
#define CHECK(c) do { checks++; if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); bad++; } } while (0)

int main(int argc, char **argv) {
    const char *want_v = argc > 1 && argv[1][0] ? argv[1] : 0, *want_r = argc > 2 && argv[2][0] ? argv[2] : 0;
    struct ts *T = ts_get();
    CHECK(T != 0);
    if (!T) return 1;
    /* the host with no context for this connection */
    char *v = host_string(T, 0x1F00, NIL), *r = host_string(T, 0x1F01, NIL), *ver = host_string(T, 0x1F02, NIL);
    char *glsl = host_string(T, 0x8B8C, NIL), *ext = host_string(T, 0x1F03, NIL);
    printf("vendor=[%s] renderer=[%s]\nversion=[%s]\nglsl=[%s]\n", v ? v : "(null)", r ? r : "(null)", ver ? ver : "(null)", glsl ? glsl : "(null)");
    CHECK(v && v[0]); CHECK(r && r[0]); CHECK(ver && !strncmp(ver, "OpenGL ES 3.", 12)); CHECK(glsl && !strncmp(glsl, "OpenGL ES GLSL ES 3.", 20)); CHECK(ext != 0);
    if (want_v) CHECK(v && !strcmp(v, want_v));
    if (want_r) CHECK(r && !strcmp(r, want_r));
    if (!want_r) CHECK(r && !strstr(r, "Mali-T760"));      /* (the invented default the first patch had) */
    /* the guest's own fallback: the same strings, and not NULL for any of the five */
    for (unsigned n = 0x1F00; n <= 0x1F03; n++) { const GLubyte *s = str_fallback(n); CHECK(s != 0); }
    CHECK(str_fallback(0x8B8C) != 0);
    CHECK(r && !strcmp((const char *)str_fallback(0x1F01), r));
    CHECK(str_fallback(0x1234) == 0);
    printf("strings with no context: %d checks, %d failed\n", checks, bad);
    return bad != 0;
}
