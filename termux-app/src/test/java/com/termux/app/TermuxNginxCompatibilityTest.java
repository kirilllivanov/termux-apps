package com.termux.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TermuxNginxCompatibilityTest {
    private static final String OLD = "/data/data/com.termux/files/usr";
    private static final String NEW = "/data/data/com.termxx/files/usr";

    @Test
    public void fixesNginxCompiledPidConfigAndLogPathsWithoutChangingSize() {
        byte[] binary = ("ELF--pid-path=" + OLD + "/tmp/nginx.pid|"
            + "--conf-path=" + OLD + "/etc/nginx/nginx.conf|"
            + "--error-log-path=" + OLD + "/var/log/nginx/error.log").getBytes(StandardCharsets.UTF_8);
        int length = binary.length;

        assertEquals(3, TermuxNginxCompatibility.replacePrefix(binary));
        String patched = new String(binary, StandardCharsets.UTF_8);
        assertTrue(patched.contains(NEW + "/tmp/nginx.pid"));
        assertTrue(patched.contains(NEW + "/etc/nginx/nginx.conf"));
        assertTrue(patched.contains(NEW + "/var/log/nginx/error.log"));
        assertFalse(patched.contains(OLD));
        assertEquals(length, binary.length);
    }

    @Test
    public void doesNotTouchUnrelatedTermuxPackageNamesOrAlreadyPatchedPaths() {
        String config = "package=com.termux; pid " + NEW + "/tmp/nginx.pid;";
        byte[] bytes = config.getBytes(StandardCharsets.UTF_8);
        byte[] original = bytes.clone();
        assertEquals(0, TermuxNginxCompatibility.replacePrefix(bytes));
        assertArrayEquals(original, bytes);
    }

    @Test
    public void fixesConfigPathButKeepsOtherUserConfiguration() {
        byte[] bytes = ("worker_processes 1;; "
            + "root " + OLD + "/share/nginx/html;; "
            + "server_name com.termux.example;; ").getBytes(StandardCharsets.UTF_8);
        assertEquals(1, TermuxNginxCompatibility.replacePrefix(bytes));
        String patched = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(patched.contains(NEW + "/share/nginx/html"));
        assertTrue(patched.contains("server_name com.termux.example;"));
        assertEquals(0, TermuxNginxCompatibility.replacePrefix(bytes));
    }
}
