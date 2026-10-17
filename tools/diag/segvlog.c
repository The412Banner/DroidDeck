// Diagnostic: on SIGSEGV/SIGBUS/SIGILL, write the faulting address, the instruction pointer, a
// frame-pointer backtrace and the stack's words to $SEGVLOG_FILE as raw addresses, then a copy of
// /proc/self/maps to resolve them (module+offset) afterwards, then die as before. Later SIGSEGV
// handlers (the game's breakpad) are refused so this one sees the fault first - only once it is set up.
// The handler calls only async-signal-safe functions: no dladdr or stdio there.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *);
static int out = -1;
static int armed;
static int probe[2] = {-1, -1};

static void put(const char *s) {
  if (out >= 0) write(out, s, strlen(s));
}

static void put_hex(unsigned long v) {
  char buf[2 + 2 * sizeof v];
  int n = sizeof buf;
  do {
    buf[--n] = "0123456789abcdef"[v & 15];
    v >>= 4;
  } while (v && n > 2);
  buf[--n] = 'x';
  buf[--n] = '0';
  if (out >= 0) write(out, buf + n, sizeof buf - n);
}

static void put_dec(long v) {
  char buf[24];
  int n = sizeof buf;
  unsigned long u = v < 0 ? -(unsigned long)v : (unsigned long)v;
  do {
    buf[--n] = '0' + u % 10;
    u /= 10;
  } while (u);
  if (v < 0) buf[--n] = '-';
  if (out >= 0) write(out, buf + n, sizeof buf - n);
}

static void line(const char *tag, void *pc) {
  put(tag);
  put(" ");
  put_hex((unsigned long)pc);
  put("\n");
}

static void copy_maps(void) {
  char buf[4096];
  int fd = open("/proc/self/maps", O_RDONLY);
  if (fd < 0) return;
  put("--- /proc/self/maps\n");
  ssize_t n;
  while ((n = read(fd, buf, sizeof buf)) > 0)
    if (out >= 0) write(out, buf, n);
  close(fd);
}

// A word the fault may have left unmapped: write() answers EFAULT where a load would fault again in here.
static int safe_word(void *const *addr, void **val) {
  if (probe[1] < 0 || write(probe[1], addr, sizeof *addr) != (ssize_t)sizeof *addr) return 0;
  return read(probe[0], val, sizeof *val) == (ssize_t)sizeof *val;
}

static void handler(int sig, siginfo_t *si, void *ctx) {
  ucontext_t *uc = ctx;
#if defined(__i386__)
  void *pc = (void *)uc->uc_mcontext.gregs[REG_EIP];
  void **fp = (void **)uc->uc_mcontext.gregs[REG_EBP];
#else
  void *pc = (void *)uc->uc_mcontext.gregs[REG_RIP];
  void **fp = (void **)uc->uc_mcontext.gregs[REG_RBP];
#endif
  put("=== pid ");
  put_dec(getpid());
  put(" signal ");
  put_dec(sig);
  put(" code ");
  put_dec(si->si_code);
  put(" fault addr ");
  put_hex((unsigned long)si->si_addr);
  put("\n");
  line("pc", pc);
  // Leaf functions (strlen, memcpy) keep no frame: the caller's return address is on the stack.
#if defined(__i386__)
  void **sp = (void **)uc->uc_mcontext.gregs[REG_ESP];
#else
  void **sp = (void **)uc->uc_mcontext.gregs[REG_RSP];
#endif
  for (int i = 0; i < 48 && sp; i++) {
    void *word;
    if (!safe_word(sp + i, &word)) break;
    if (word) line("  sp", word);
  }
  for (int i = 0; i < 24 && fp && ((unsigned long)fp & 3) == 0; i++) {
    void *ret, *next;
    if (!safe_word(fp + 1, &ret) || !ret) break;
    line("  at", ret);
    if (!safe_word(fp, &next) || (void **)next <= fp) break;
    fp = next;
  }
  copy_maps();
  // Die as the signal would have: default action, then the signal again (delivered on return, as
  // it is blocked in here; a hardware fault would also recur on the faulting instruction).
  struct sigaction dfl;
  memset(&dfl, 0, sizeof dfl);
  dfl.sa_handler = SIG_DFL;
  real_sigaction(sig, &dfl, NULL);
  raise(sig);
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
  if (!real_sigaction) real_sigaction = dlsym(RTLD_NEXT, "sigaction");
  if (armed && act && (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL) && act->sa_sigaction != handler) {
    if (old) real_sigaction(sig, NULL, old);
    return 0;
  }
  return real_sigaction(sig, act, old);
}

__attribute__((constructor)) static void init(void) {
  const char *f = getenv("SEGVLOG_FILE");
  if (!f) return;
  real_sigaction = dlsym(RTLD_NEXT, "sigaction");
  if (!real_sigaction) return;
  out = open(f, O_WRONLY | O_CREAT | O_APPEND, 0644);
  if (out < 0) return;
  if (pipe2(probe, O_CLOEXEC | O_NONBLOCK) != 0) probe[0] = probe[1] = -1;
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = handler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  armed = real_sigaction(SIGSEGV, &sa, NULL) == 0 && real_sigaction(SIGBUS, &sa, NULL) == 0 &&
          real_sigaction(SIGILL, &sa, NULL) == 0;
}
