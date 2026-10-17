// The handler calls only async-signal-safe functions: no dladdr or stdio there.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *);
static int out = -1;
static int armed;
static int probe[2] = {-1, -1};
static int logged;
// Handlers the program installs run after the log: Wine and JIT runtimes recover from these faults.
static struct sigaction chained[3];
static int have_chained[3];

static int fault_index(int sig) {
  return sig == SIGSEGV ? 0 : sig == SIGBUS ? 1 : sig == SIGILL ? 2 : -1;
}

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
  int fd = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC);
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

static void log_fault(int sig, siginfo_t *si, void *ctx) {
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
}

static void handler(int sig, siginfo_t *si, void *ctx) {
  int saved_errno = errno;
  int i = fault_index(sig);
  struct sigaction *next = i >= 0 && have_chained[i] ? &chained[i] : NULL;
  int chains = next && ((next->sa_flags & SA_SIGINFO) ? next->sa_sigaction != NULL
                                                      : next->sa_handler != SIG_DFL && next->sa_handler != SIG_IGN);
  // The cap is for faults the program recovers from; one that ends the process here is always logged.
  if (!chains || logged < 32) {
    logged++;
    log_fault(sig, si, ctx);
  }
  if (chains) {
    errno = saved_errno;
    if (next->sa_flags & SA_SIGINFO)
      next->sa_sigaction(sig, si, ctx);
    else
      next->sa_handler(sig);
    return;
  }
  struct sigaction dfl;
  memset(&dfl, 0, sizeof dfl);
  dfl.sa_handler = SIG_DFL;
  real_sigaction(sig, &dfl, NULL);
  raise(sig);
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
  if (!real_sigaction) real_sigaction = dlsym(RTLD_NEXT, "sigaction");
  int i = fault_index(sig);
  if (armed && i >= 0 && act && act->sa_sigaction != handler) {
    if (old) {
      if (have_chained[i]) *old = chained[i];
      else real_sigaction(sig, NULL, old);
    }
    chained[i] = *act;
    have_chained[i] = 1;
    return 0;
  }
  if (armed && i >= 0 && !act && old && have_chained[i]) {
    *old = chained[i];
    return 0;
  }
  return real_sigaction(sig, act, old);
}

__attribute__((constructor)) static void init(void) {
  const char *f = getenv("SEGVLOG_FILE");
  if (!f) return;
  real_sigaction = dlsym(RTLD_NEXT, "sigaction");
  if (!real_sigaction) return;
  // Raw syscalls: other preloads interpose open(), and the handler must not run their code.
  out = (int)syscall(SYS_openat, AT_FDCWD, f, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
  if (out < 0) return;
  if (pipe2(probe, O_CLOEXEC | O_NONBLOCK) != 0) probe[0] = probe[1] = -1;
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = handler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  armed = real_sigaction(SIGSEGV, &sa, NULL) == 0 && real_sigaction(SIGBUS, &sa, NULL) == 0 &&
          real_sigaction(SIGILL, &sa, NULL) == 0;
}
