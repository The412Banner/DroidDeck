/* pulsecore/module.h keeps a libltdl handle in pa_module; the module never touches it, so the
 * handle type is all that is needed here. */
#ifndef STEAMDECK_LTDL_STUB_H
#define STEAMDECK_LTDL_STUB_H
typedef void *lt_dlhandle;
#endif
