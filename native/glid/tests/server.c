/* stands in for libglserverd: asks the driver through the dynamic symbol, as the real one does */
#include <stdio.h>
#include <string.h>
extern const unsigned char *glGetString(unsigned int name);
static int bad;
static void is(unsigned int name, const char *want, const char *what) {
    const unsigned char *s = glGetString(name);
    const char *got = s ? (const char *)s : "(null)";
    if (strcmp(got, want)) { printf("FAIL %s: want '%s', got '%s'\n", what, want, got); bad++; }
}
int main(int argc, char **argv) {
    /* argv: vendor renderer, the answers expected for GL_VENDOR / GL_RENDERER */
    if (argc != 3) return 2;
    is(0x1F00, argv[1], "GL_VENDOR");
    is(0x1F01, argv[2], "GL_RENDERER");
    is(0x1F02, "OpenGL ES 3.2 V@0702.0", "GL_VERSION (never changed)");
    const unsigned char *x = glGetString(0x1F03);
    if (x) { printf("FAIL unknown name: got '%s'\n", x); bad++; }
    return bad ? 1 : 0;
}
