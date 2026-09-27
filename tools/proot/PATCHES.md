# proot patches carried by the app

Built by `tools/proot/build.sh` with the NDK on top of termux/proot `4dba3afb` (Termux's
5.1.107-70 package, the proot the Linux runtime ships at `opt/android-host/proot`; every
`file.c:line` site in that binary matches this commit). talloc is linked in statically from
Samba's release tarball, so the apk carries one self-contained `libproot.so` plus
`libproot-loader.so`, and `LinuxRuntime` prefers them over the runtime's copy.

Ported from WinNative (`feature/proot-enhancements`, e7af0f24):

- `0001-tracee-lookup-by-pid.patch` - tracees are hashed by pid, so each ptrace stop finds its
  tracee without walking one list entry per thread, and terminated tracees are swept only after a
  termination.
- `0002-canon-resolve-parent-at-once.patch` - a clean absolute guest path at least three
  directories deep under the rootfs binding alone has its parent opened once with `O_PATH` and
  taken as canonical when `/proc/self/fd` names the path proot would build, replacing an `lstat`
  per component. The final component of a call that does not follow it is no longer `lstat`ed.
  Extensions still see the parent's host path, and the fast path stays off while the f2fs
  workaround is active.
- `0003-clone3-flags-read-guard.patch` - a thread created with `clone3` keeps the flags it
  inherited when `struct clone_args` cannot be read, instead of being tracked as a fork.

Ported from WinNative (`main`, 53836ca9, "Fix/performance and vac"):

- `0004-seccomp-filter-by-argument.patch` - the seccomp filter traces `prctl` only for
  `PR_SET_DUMPABLE`, `setrlimit` only for `RLIMIT_STACK` and `prlimit64` only when it sets a new
  `RLIMIT_STACK`, the only cases proot acts on; every other call runs without a stop. `uname` is
  traced by the core only on x86_64, the one arch it rewrites; kompat still adds it for
  `--kernel-release`.
- `0005-seccomp-skip-unneeded-sysexit.patch` - a seccomp stop fetches the registers once and
  looks the filter flags up by syscall number, instead of a `PTRACE_GETEVENTMSG` and a second
  register fetch. The flags table is built from proot's list merged with the enabled extensions'
  (kompat and fake_id0 here), so it is exactly what the filter reports. `wait4`/`waitpid` left to
  the kernel and `accept`/`accept4` without a sockaddr skip their exit stop, unless an extension
  replaced the syscall (kompat turning `accept4` into `accept`).
- `0006-clone3-exit-signal.patch` - `clone3` flags and exit signal are read as the 64-bit fields
  of `struct clone_args`, so a `clone3` fork is told apart from a thread when proot decides how the
  child is traced.
- `0007-proc-self-thread-group.patch` - tracees track their thread group, `/proc/self` names it
  instead of the calling thread, and `/proc/thread-self` resolves to `/proc/<tgid>/task/<tid>`.
- `0008-fchmodat2-openat2.patch` - `fchmodat2` paths are translated (honouring
  `AT_SYMLINK_NOFOLLOW`), `openat2` answers `ENOSYS` so callers fall back to the translated
  `openat`, and syscall numbers past the end of a table are rejected instead of read.
- `0009-tracee-relatives-sweep.patch` - tracees count their children, so a terminating thread
  with no children or ptracees no longer walks every tracee; the per-stop memory collector is
  emptied instead of freed and reallocated.
