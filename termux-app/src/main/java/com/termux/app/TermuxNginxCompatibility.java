package com.termux.app;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Fix the absolute Termux prefix compiled into nginx packages for the parallel
 * com.termxx app. Official packages are built for /data/data/com.termux.
 *
 * The replacement has the same byte length, so it does not change ELF offsets,
 * section sizes or instruction locations. Only the known filesystem prefix in
 * the nginx binary and its main config is changed.
 */
final class TermuxNginxCompatibility {
    private static final byte[] ORIGINAL_PREFIX =
        "/data/data/com.termux/files/usr".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LOCAL_PREFIX =
        TermuxConstants.PREFIX_PATH.getBytes(StandardCharsets.US_ASCII);

    private TermuxNginxCompatibility() {}

    static void repairInBackground() {
        new Thread(TermuxNginxCompatibility::repairInstalledNginx, "termux-nginx-prefix-fix").start();
    }

    private static void repairInstalledNginx() {
        if (ORIGINAL_PREFIX.length != LOCAL_PREFIX.length) {
            Log.w(TermuxConstants.LOG_TAG, "Cannot relocate nginx: prefix lengths differ");
            return;
        }

        File nginx = new File(TermuxConstants.BIN_PATH, "nginx");
        if (!nginx.isFile()) return;

        try {
            boolean changed = patchFile(nginx, true);
            File config = new File(TermuxConstants.PREFIX_PATH, "etc/nginx/nginx.conf");
            changed |= patchFile(config, false);

            // The compiled nginx pid path points here after relocation.
            ensureDirectory(new File(TermuxConstants.PREFIX_PATH, "tmp"));
            ensureDirectory(new File(TermuxConstants.PREFIX_PATH, "var/log/nginx"));

            if (changed) {
                Log.i(TermuxConstants.LOG_TAG, "Relocated installed nginx paths to " + TermuxConstants.PREFIX_PATH);
            }
        } catch (IOException | ErrnoException e) {
            // Do not prevent the terminal itself from starting.
            Log.e(TermuxConstants.LOG_TAG, "Could not repair installed nginx paths", e);
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create nginx directory: " + directory);
        }
    }

    private static boolean patchFile(File file, boolean requireElf) throws IOException, ErrnoException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) return false;

        byte[] content = Files.readAllBytes(file.toPath());
        if (requireElf && (content.length < 4 || content[0] != 0x7f ||
            content[1] != 'E' || content[2] != 'L' || content[3] != 'F')) return false;
        if (replacePrefix(content) == 0) return false;

        int originalMode = Os.stat(file.getAbsolutePath()).st_mode & 07777;
        File temporary = File.createTempFile(".nginx-prefix-", ".tmp", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(content);
                output.getFD().sync();
            }
            Os.chmod(temporary.getAbsolutePath(), originalMode);
            // Same directory and filesystem; rename replaces the old inode atomically,
            // without risking a half-written executable if the process is interrupted.
            Os.rename(temporary.getAbsolutePath(), file.getAbsolutePath());
            return true;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TermuxConstants.LOG_TAG, "Cannot remove temporary nginx file: " + temporary);
            }
        }
    }

    /** In-place, length-preserving replacement; exposed for JVM unit tests. */
    static int replacePrefix(byte[] bytes) {
        if (ORIGINAL_PREFIX.length != LOCAL_PREFIX.length) return 0;
        int replacements = 0;
        for (int i = 0; i <= bytes.length - ORIGINAL_PREFIX.length; i++) {
            boolean matches = true;
            for (int j = 0; j < ORIGINAL_PREFIX.length; j++) {
                if (bytes[i + j] != ORIGINAL_PREFIX[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                System.arraycopy(LOCAL_PREFIX, 0, bytes, i, LOCAL_PREFIX.length);
                replacements++;
                i += ORIGINAL_PREFIX.length - 1;
            }
        }
        return replacements;
    }
}
