/* host test of N/gralloc_qcom.c (N = 24..29): gcc -std=gnu11 -fsanitize=address,undefined -IN tests/gralloc_qcom_test.c -ldl ; ASAN_OPTIONS=detect_leaks=0 ./a.out */
#include "gralloc_qcom.c"
#include <stdio.h>
#include <string.h>
#include <assert.h>
static int regs, unregs, locks, frees, allocs;
static int f_reg(const struct gralloc_module*m,const void*h){(void)m;(void)h;regs++;return 0;}
static int f_unreg(const struct gralloc_module*m,const void*h){(void)m;(void)h;unregs++;return 0;}
static int f_lock(const struct gralloc_module*m,const void*h,int u,int l,int t,int w,int hh,void**v){(void)m;(void)h;(void)u;(void)l;(void)t;(void)w;(void)hh;(void)v;locks++;return 0;}
static int f_unlock(const struct gralloc_module*m,const void*h){(void)m;(void)h;return 0;}
static int f_alloc(struct alloc_device*d,int w,int h,int fmt,int u,const void**out,int*stride){(void)d;(void)w;(void)h;(void)fmt;(void)u;struct rom_handle*q=calloc(1,sizeof*q);q->version=12;q->numFds=1;q->numInts=8;q->fd=5;q->magic=MAGIC;q->size=4096;*out=q;*stride=w;allocs++;return 0;}
static int f_free(struct alloc_device*d,const void*h){(void)d;free((void*)h);frees++;return 0;}
int main(void){
  static struct gralloc_module fake={.registerBuffer=f_reg,.unregisterBuffer=f_unreg,.lock=f_lock,.unlock=f_unlock};
  real=&fake;
  struct alloc_device rd={.alloc=f_alloc,.free=f_free}; struct wrap_device wd={.real=&rd};
  void*v;
  /* registered handle copied in-process, both registered and unregistered: one real unregister, one free, no UAF */
  int *a=calloc(W_INTS,4); a[W_VERSION]=12;a[W_NUMFDS]=1;a[W_NUMINTS]=W_INTS-4;a[W_MAGIC]=QMAGIC;a[W_FD]=7; a[W_ROM_PID]=1234; /* from another process */
  assert(w_register(0,a)==0 && regs==1);
  int *c=calloc(W_INTS,4); memcpy(c,a,W_INTS*4);           /* a clone of a registered handle */
  assert(w_register(0,c)==0 && regs==1);                    /* shares, no second register */
  assert(w_lock(0,c,0,0,0,1,1,&v)==0);
  assert(w_unregister(0,a)==0 && unregs==0);               /* c still holds it */
  assert(w_lock(0,c,0,0,0,1,1,&v)==0);
  assert(w_unregister(0,c)==0 && unregs==1);               /* last one releases */
  assert(w_unregister(0,c)==0 && unregs==1);               /* again: nothing */
  assert(w_lock(0,c,0,0,0,1,1,&v)==-22);                    /* stale copy: refused, not followed */
  /* clone that never registered unregisters: no effect */
  assert(w_register(0,a)==0 && regs==2);
  int *u=calloc(W_INTS,4); memcpy(u,a,W_INTS*4);
  assert(w_unregister(0,u)==0 && unregs==1);
  assert(w_unregister(0,a)==0 && unregs==2);
  assert(w_lock(0,u,0,0,0,1,1,&v)==-22);
  /* allocated here: clone registered/unregistered, then freed once; clone's lock after the free refused */
  const void*h; int st; assert(d_alloc(&wd.dev,10,10,1,0,&h,&st)==0);
  int *cl=calloc(W_INTS,4); memcpy(cl,h,W_INTS*4);
  assert(w_register(0,cl)==0 && w_unregister(0,cl)==0 && regs==2 && unregs==2);
  assert(w_lock(0,cl,0,0,0,1,1,&v)==0);
  assert(d_free(&wd.dev,h)==0 && frees==1);
  assert(w_lock(0,cl,0,0,0,1,1,&v)==-22);
  /* a handle from another process after the first: registers anew */
  assert(live_find(0)>=0);
  puts("ok"); return 0; }
