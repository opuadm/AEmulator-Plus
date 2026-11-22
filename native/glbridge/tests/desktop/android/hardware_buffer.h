#pragma once
#include <stdint.h>
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct { uint32_t width, height, layers, format; uint64_t usage; uint32_t stride, rfu0; uint64_t rfu1; } AHardwareBuffer_Desc;
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
#define AHARDWAREBUFFER_USAGE_CPU_READ_RARELY 2ull
#define AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY 0x20ull
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE 0x100ull
#define AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT 0x200ull
/* (no hardware buffers on this machine: the host test only exercises the readback path) */
static inline int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *d, AHardwareBuffer **o) { (void)d; *o = 0; return -1; }
static inline void AHardwareBuffer_describe(const AHardwareBuffer *b, AHardwareBuffer_Desc *d) { (void)b; (void)d; }
static inline int AHardwareBuffer_lock(AHardwareBuffer *b, uint64_t u, int f, const void *r, void **v) { (void)b; (void)u; (void)f; (void)r; (void)v; return -1; }
static inline int AHardwareBuffer_unlock(AHardwareBuffer *b, int *f) { (void)b; (void)f; return -1; }
static inline void AHardwareBuffer_release(AHardwareBuffer *b) { (void)b; }
