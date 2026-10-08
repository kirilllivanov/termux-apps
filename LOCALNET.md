# Termux LocalNet branch

This branch is based directly on Google Play release `termux-app-googleplay.2026.06.21`
(commit `888ee58d0abaa6d0db00a1fded093755d136ec46`) and keeps all LocalNet changes in one commit.

Key changes:

- Android application id: `com.termxx` so it can coexist with Play Store `com.termux`.
- Java namespace remains `com.termux`.
- Android 17 `ACCESS_LOCAL_NETWORK` manifest + runtime permission request.
- `MANAGE_EXTERNAL_STORAGE` setup no longer depends on `termux-am`.
- `termux-setup-storage` is replaced with a no-`am` wrapper after bootstrap install and on resume.
- arm64-v8a release build signed with the repository test key.
- Google Play bootstrap is patched at build time for `com.termxx`, while Java class names such as
  `com.termux.termuxam.Am` are preserved.

Build with:

```bash
./gradlew :termux-app:assembleRelease
```

The build automatically downloads and patches the matching Google Play bootstrap.

Note: official package repository packages are built for the original Termux package/prefix. This branch is intended
for the custom parallel Google Play build and should be treated as a fork when upgrading core Termux packages.

## nginx and other precompiled packages

Official Termux packages are built for `/data/data/com.termux/files/usr`,
but this application uses `/data/data/com.termxx/files/usr`. In particular,
nginx has an absolute `--pid-path` compiled into its ELF executable, which
causes `open() ".../com.termux/.../tmp/nginx.pid" failed (2)`.

On returning to the app, LocalNet checks the installed `$PREFIX/bin/nginx`
and corrects this exact old filesystem prefix in the ELF and the main
`$PREFIX/etc/nginx/nginx.conf` file (without altering unrelated package
names). The patch is atomic, preserves permissions, skips symlinks, creates
`$PREFIX/tmp` and the nginx log directory, and runs off the UI thread.
It does not touch the other `com.termux` app. A restart/reopen of LocalNet
after installing or upgrading nginx triggers the check again.

Verify from inside the app with `nginx -V 2>&1` (the compiled --pid-path
should contain `com.termxx`), then try `nginx -t` and `nginx`.
This is a targeted nginx fix, **not** a guarantee that every official
Termux package works under a renamed application ID. Some packages may
need to be rebuilt for `com.termxx`.
