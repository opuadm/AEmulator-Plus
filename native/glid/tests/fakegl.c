/* stands in for the phone's libGLESv2.so: the strings of the real driver */
#define EXPORT __attribute__((visibility("default")))
EXPORT const unsigned char *glGetString(unsigned int name) {
    switch (name) {
    case 0x1F00: return (const unsigned char *)"Qualcomm";
    case 0x1F01: return (const unsigned char *)"Adreno (TM) 740";
    case 0x1F02: return (const unsigned char *)"OpenGL ES 3.2 V@0702.0";
    default: return 0;
    }
}
