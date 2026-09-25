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
