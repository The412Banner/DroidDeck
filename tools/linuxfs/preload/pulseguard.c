/* In Deck mode (-steamos3) the x86-64 client's SteamOS audio manager passes libpulse the NULL operation of a
 * failed request, and libpulse aborts on it ("Assertion 'o' failed ... pa_operation_get_state()"). */
#if defined(__x86_64__) || defined(__i386__)
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

typedef struct pa_operation pa_operation;

#define PA_OPERATION_CANCELLED 2

int pa_operation_get_state(pa_operation *o) {
    static int (*next)(pa_operation *);
    if (!o)
        return PA_OPERATION_CANCELLED;
    if (!next)
        next = (int (*)(pa_operation *))dlsym(RTLD_NEXT, "pa_operation_get_state");
    return next ? next(o) : PA_OPERATION_CANCELLED;
}

void pa_operation_unref(pa_operation *o) {
    static void (*next)(pa_operation *);
    if (!o)
        return;
    if (!next)
        next = (void (*)(pa_operation *))dlsym(RTLD_NEXT, "pa_operation_unref");
    if (next)
        next(o);
}

void pa_operation_cancel(pa_operation *o) {
    static void (*next)(pa_operation *);
    if (!o)
        return;
    if (!next)
        next = (void (*)(pa_operation *))dlsym(RTLD_NEXT, "pa_operation_cancel");
    if (next)
        next(o);
}
#else
typedef int pulseguard_arm_build_has_nothing_to_do;
#endif
