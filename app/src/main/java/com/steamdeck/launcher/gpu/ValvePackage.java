package com.steamdeck.launcher.gpu;

import com.steamdeck.launcher.core.TarZst;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * One Arch package from Valve's SteamOS package server, fetched at a pinned version and checked
 * against a pinned sha256 before anything in it is used. The Qualcomm drivers come this way rather
 * than in the apk: they are Qualcomm's binaries, so the app fetches them where Valve publishes them
 * and never re-hosts them. A package Valve has since removed or replaced fails the check.
 */
final class ValvePackage {
    interface Progress {
        void onProgress(long done, long total);
    }

    private ValvePackage() {}

    /**
     * Download {@code url} into {@code cache}, verify it and unpack it into {@code into}.
     * @throws IllegalArgumentException when the download does not match {@code sha256}
     */
    static void fetch(String url, String sha256, long size, File cache, File into, Progress progress) throws IOException {
        download(url, size, cache, progress);
        String actual = sha256(cache);
        if (!sha256.equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("The download does not match the pinned driver (sha256 "
                    + actual.substring(0, 12) + "…). Valve may have replaced it; nothing was installed.");
        }
        boolean ok;
        try (InputStream in = new FileInputStream(cache)) {
            ok = TarZst.extract(in, into);
        }
        if (!ok) throw new IOException("could not unpack the driver package");
    }

    private static void download(String url, long size, File target, Progress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Valve's server answered HTTP " + code);
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : size;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    done += r;
                    if (progress != null) progress.onProgress(done, total);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
