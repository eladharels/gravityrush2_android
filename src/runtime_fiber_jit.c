/* android port: libSceFiber for Uncharted 3 when the game's code runs in the JIT (FEXCore,
 * cpu/guest_cpu_fex.cpp) instead of on the host CPU. Same behaviour as runtime_fiber.c, whose
 * notes apply; only how a context is switched differs.
 *
 * On x86-64 hosts runtime_fiber.c swaps the host registers itself (the guest's code and the
 * runtime share one stack and register file there). Under the JIT the guest's registers live in
 * the JIT's per-thread CPU state and the runtime runs on its own host stack, so a switch is a
 * replacement of guest registers: the host function the guest called saves the guest context it
 * would return to (guest_cpu_context_save) and has the guest resume with another one
 * (guest_cpu_context_load) when it returns. No host frame is left behind on a fiber's stack, so:
 *   - the context left behind is complete when the host function saves it: it is marked
 *     suspended at once, and another thread may resume it right away (on its own registers);
 *   - a fiber moves between threads freely; the guest TCB (GS) stays per thread, as on the PS4.
 * A new fiber's stack holds its entry address (popped by the `ret` of the host stub) and, above
 * it, the return address of the entry function: a host trap that stops the run, as on x86-64.
 *
 * Switches are only sound with one host-to-guest call active on the thread (guest_cpu_depth 1:
 * the thread's entry). A switch inside a host callback into guest code would leave that host
 * frame waiting for a guest return that happens on another stack; it is reported and refused. */
#include "guest_cpu.h"
#if !defined(GUEST_CPU_NATIVE)
#include "runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FIBER_ERROR_NULL ((int32_t)0x80590001)
#define FIBER_ERROR_ALIGNMENT ((int32_t)0x80590002)
#define FIBER_ERROR_RANGE ((int32_t)0x80590003)
#define FIBER_ERROR_INVALID ((int32_t)0x80590004)
#define FIBER_ERROR_PERMISSION ((int32_t)0x80590005)
#define FIBER_ERROR_STATE ((int32_t)0x80590006)
#define FIBER_CONTEXT_MINIMUM 512
#define FIBER_MAGIC 0x46334355u /* "UC3F" */

#if defined(__aarch64__)
#define FIBER_RELAX() __asm__ __volatile__("yield" ::: "memory")
#else
#define FIBER_RELAX() ((void)0)
#endif

enum { FIBER_IDLE, FIBER_RUNNING, FIBER_SWITCHING, FIBER_SUSPENDED };
typedef struct {
    uint32_t magic, state;
    uint64_t entry, arg_init, arg_run;
    unsigned char *stack_low; uint64_t stack_size;
    uint64_t *arg_out;  /* where a resumer writes argOnRun (the suspended call's out pointer) */
    GuestContext regs;
    char name[32];
} Fiber;
_Static_assert(sizeof(Fiber) <= 256, "SceFiber is 256 bytes");

typedef struct {
    GuestContext regs;     /* the thread's own context while it runs fibers */
    Fiber *current;        /* running fiber, NULL on the thread's own context */
    uint64_t *run_return;  /* sceFiberRun's argOnReturn */
} FiberThread;
static _Thread_local FiberThread fiber_thread;

static uint64_t created, switches;
static uintptr_t returned_trap;

static void entry_returned(void) {
    Fiber *f=fiber_thread.current;
    fprintf(stderr,"STOP: fiber \"%s\" returned from its entry function (it must return to the thread)\n",
            f ? f->name : "?");
    runtime_report();
    exit(23);
}

/* Switches only where no host frame belongs to the guest stack being left. */
static int switch_allowed(const char *call) {
    const unsigned depth=guest_cpu_depth();
    if (depth==1) return 1;
    static int reported;
    if (!__atomic_exchange_n(&reported,1,__ATOMIC_RELAXED))
        fprintf(stderr,"Runtime: %s with %u host-to-guest calls active: refused (fibers need 1)\n",call,depth);
    return 0;
}

/* Takes a fiber that is idle or suspended for running. Delivers argOnRun to it (its entry
 * argument or its suspended call's out pointer). */
static int32_t claim(Fiber *f, uint64_t arg) {
    if (!f) return FIBER_ERROR_NULL;
    if ((uintptr_t)f & 7) return FIBER_ERROR_ALIGNMENT;
    if (f->magic!=FIBER_MAGIC) return FIBER_ERROR_INVALID;
    for (;;) {
        uint32_t state=__atomic_load_n(&f->state,__ATOMIC_ACQUIRE);
        if (state==FIBER_SWITCHING) { FIBER_RELAX(); continue; }
        if (state!=FIBER_IDLE && state!=FIBER_SUSPENDED) return FIBER_ERROR_STATE;
        if (__atomic_compare_exchange_n(&f->state,&state,FIBER_RUNNING,0,__ATOMIC_ACQUIRE,__ATOMIC_RELAXED)) {
            if (state==FIBER_IDLE) { f->arg_run=arg; f->regs.rdi=f->arg_init; f->regs.rsi=arg; }
            else if (f->arg_out) *f->arg_out=arg;
            return 0;
        }
    }
}

/* Leaves `self` (the running fiber) for the context `next`: saved, then suspended at once. */
static void leave(Fiber *self, const GuestContext *next) {
    __atomic_store_n(&self->state,FIBER_SWITCHING,__ATOMIC_RELAXED);
    guest_cpu_context_save(&self->regs);
    guest_cpu_context_load(next);
    __atomic_store_n(&self->state,FIBER_SUSPENDED,__ATOMIC_RELEASE);
}

static ABI int32_t fiber_initialize(Fiber *f, const char *name, uint64_t entry, uint64_t arg_init,
                                    void *context, uint64_t size, const void *opt, uint32_t flags, uint32_t build) {
    (void)opt; (void)flags; (void)build;
    if (!f || !entry) return FIBER_ERROR_NULL;
    if ((uintptr_t)f & 7) return FIBER_ERROR_ALIGNMENT;
    if (!context) {
        fprintf(stderr,"STOP: sceFiberInitialize(\"%s\") without a context stack is not supported\n",name ? name : "");
        runtime_report();
        exit(21);
    }
    if ((uintptr_t)context & 15 || size & 15) return FIBER_ERROR_ALIGNMENT;
    if (size<FIBER_CONTEXT_MINIMUM) return FIBER_ERROR_RANGE;
    if (!__atomic_load_n(&returned_trap,__ATOMIC_ACQUIRE))
        __atomic_store_n(&returned_trap,guest_cpu_host_function((void *)entry_returned),__ATOMIC_RELEASE);
    memset(f,0,sizeof(*f));
    f->magic=FIBER_MAGIC; f->state=FIBER_IDLE;
    f->entry=entry; f->arg_init=arg_init;
    f->stack_low=context; f->stack_size=size;
    snprintf(f->name,sizeof(f->name),"%s",name ? name : "");
    /* The stub's `ret` pops the entry; the entry then sees the trap as its return address, with
     * RSP = top - 8 (16-byte aligned plus the return address, as after a call). */
    uint64_t *top=(uint64_t *)(((uintptr_t)(f->stack_low+size))&~(uintptr_t)15);
    top[-1]=returned_trap;
    top[-2]=entry;
    f->regs.rsp=(uint64_t)(uintptr_t)(top-2);
    f->regs.mxcsr=0x1F80; f->regs.fcw=0x037F; /* FreeBSD defaults: all exceptions masked */
    if (!__atomic_fetch_add(&created,1,__ATOMIC_RELAXED))
        printf("Runtime: first fiber \"%s\" (%llu KiB stack, JIT contexts)\n",f->name,(unsigned long long)(size/1024));
    return 0;
}
static ABI int32_t fiber_opt_initialize(void *opt) {
    if (!opt) return FIBER_ERROR_NULL;
    if ((uintptr_t)opt & 7) return FIBER_ERROR_ALIGNMENT;
    memset(opt,0,128); return 0;
}
static ABI int32_t fiber_finalize(Fiber *f) {
    if (!f) return FIBER_ERROR_NULL;
    if ((uintptr_t)f & 7) return FIBER_ERROR_ALIGNMENT;
    if (f->magic!=FIBER_MAGIC) return FIBER_ERROR_INVALID;
    uint32_t state=__atomic_load_n(&f->state,__ATOMIC_ACQUIRE);
    if (state!=FIBER_IDLE && state!=FIBER_SUSPENDED) return FIBER_ERROR_STATE;
    f->magic=0; return 0;
}
/* From the thread's own context: runs the fiber until a fiber returns to this thread. The
 * guest resumes in the fiber; sceFiberRun returns (0) when a fiber returns to this thread. */
static ABI int32_t fiber_run(Fiber *f, uint64_t arg, uint64_t *arg_on_return) {
    FiberThread *t=&fiber_thread;
    if (t->current) return FIBER_ERROR_PERMISSION;
    if (!switch_allowed("sceFiberRun")) return FIBER_ERROR_PERMISSION;
    int32_t r=claim(f,arg);
    if (r) return r;
    t->current=f; t->run_return=arg_on_return;
    __atomic_fetch_add(&switches,1,__ATOMIC_RELAXED);
    guest_cpu_context_save(&t->regs);
    guest_cpu_context_load(&f->regs);
    return 0;
}
/* From a fiber: suspends it and runs another; returns (0) when something resumes this fiber. */
static ABI int32_t fiber_switch(Fiber *to, uint64_t arg, uint64_t *arg_on_run) {
    FiberThread *t=&fiber_thread;
    Fiber *self=t->current;
    if (!self) return FIBER_ERROR_PERMISSION;
    if (to==self) return FIBER_ERROR_STATE;
    if (!switch_allowed("sceFiberSwitch")) return FIBER_ERROR_PERMISSION;
    int32_t r=claim(to,arg);
    if (r) return r;
    self->arg_out=arg_on_run;
    t->current=to;
    __atomic_fetch_add(&switches,1,__ATOMIC_RELAXED);
    leave(self,&to->regs);
    return 0;
}
static ABI int32_t fiber_return_to_thread(uint64_t arg_on_return, uint64_t *arg_on_run) {
    FiberThread *t=&fiber_thread;
    Fiber *self=t->current;
    if (!self) return FIBER_ERROR_PERMISSION;
    if (!switch_allowed("sceFiberReturnToThread")) return FIBER_ERROR_PERMISSION;
    self->arg_out=arg_on_run;
    if (t->run_return) *t->run_return=arg_on_return;
    t->current=NULL;
    leave(self,&t->regs);
    return 0;
}
static ABI int32_t fiber_get_self(Fiber **out) {
    if (!out) return FIBER_ERROR_NULL;
    Fiber *self=fiber_thread.current;
    if (!self) return FIBER_ERROR_PERMISSION;
    *out=self; return 0;
}

static const RuntimeExport exports[]={
    {"_sceFiberInitializeImpl",fiber_initialize}, {"sceFiberOptParamInitialize",fiber_opt_initialize},
    {"sceFiberFinalize",fiber_finalize}, {"sceFiberRun",fiber_run}, {"sceFiberSwitch",fiber_switch},
    {"sceFiberReturnToThread",fiber_return_to_thread}, {"sceFiberGetSelf",fiber_get_self},
};
uintptr_t runtime_fiber_resolve(const char *name) { return RUNTIME_LOOKUP(exports,name); }
void runtime_fiber_report(void) {
    printf("Runtime: fibers created=%llu, switches=%llu (JIT contexts)\n",(unsigned long long)created,(unsigned long long)switches);
}
#else
typedef int runtime_fiber_jit_unused; /* x86-64 hosts: src/runtime_fiber.c */
#endif
