package com.steamdeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import com.steamdeck.launcher.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Qualcomm's own Adreno Vulkan driver for glibc Linux, the one Valve ships for the Steam Frame
 * (Snapdragon 8 Gen 3, SM8650) as {@code qualcomm-linux-graphics-driver-kgsl}. It drives the GPU
 * through {@code /dev/kgsl-3d0}, the same kernel interface Android's own driver uses, which is why
 * it can run inside the runtime on an Android kernel at all - unlike Valve's Mesa build, which
 * only speaks msm DRM.
 *
 * <p>Fetched from Valve's package server at a pinned version and checked against a pinned
 * sha256, never bundled or re-hosted: they are Qualcomm's binaries. A package Valve has since
 * removed or replaced fails the check and nothing is installed.
 *
 * <p>Three things set it apart from an imported Turnip, all handled here and in the session:
 * <ul>
 *   <li>Twelve libraries, not one. They find each other through a RUNPATH of
 *       {@code /usr/lib/adreno} built into them, so the session binds this driver's
 *       {@code adreno/} directory there ({@link #bindDir}); the runtime image is never touched.</li>
 *   <li>No X11 surface support of its own: that comes from an implicit layer,
 *       {@code VK_LAYER_WSI_xcb}, which the session enables ({@link #layerDir}).</li>
 *   <li>Its ICD manifest names {@code /usr/lib/adreno/libvulkan_adreno.so}, the guest path the
 *       bind creates.</li>
 * </ul>
 * Tested only on the Pocket FIT (Adreno 750): vulkaninfo enumerated it inside the runtime.
 */
public final class QualcommLinuxDriver {
    private static final String TAG = "QualcommLinuxDriver";

    public static final String ID = "qualcomm-kgsl-linux-20260908.2";
    public static final String NAME = "Qualcomm Adreno (Linux, games only)";
    public static final String VERSION = "20260908.2";
    public static final String FLAVOR = "qualcomm-kgsl";
    public static final String URL_STRING = "https://holo-packages.steamos.cloud/archlinux-deckard-hotfixes/release/0.4.x/"
            + "qualcomm-linux-graphics-driver-kgsl-20260908.2-1-any.pkg.tar.zst";
    public static final String SHA256 = "baf0344f10cc42ff0564d4c24a925192c8dc99ae3f3f6e691328c1833f270e63";
    public static final long SIZE = 12_639_043L;

    /** Where the driver's libraries must appear inside the session: their RUNPATH names it. */
    public static final String GUEST_DIR = "/usr/lib/adreno";
    static final String LIB_NAME = "libvulkan_adreno.so";
    private static final String ADRENO_DIR = "adreno";
    private static final String LAYER_DIR = "implicit_layer.d";

    private QualcommLinuxDriver() {}

    /** True when {@code id}'s meta.json marks it as this driver (whatever version). */
    public static boolean isQualcomm(LinuxVulkanDriverManager m, String id) {
        return FLAVOR.equals(m.getFlavor(id));
    }

    /** The host directory the session binds at {@link #GUEST_DIR}, or null for any other driver. */
    public static File bindDir(LinuxVulkanDriverManager m, String id) {
        if (!isQualcomm(m, id)) return null;
        File dir = new File(m.getDriverDir(id), ADRENO_DIR);
        return new File(dir, LIB_NAME).isFile() ? dir : null;
    }

    /** The driver's implicit layer manifests (X11 and Wayland surfaces), or null. */
    public static File layerDir(LinuxVulkanDriverManager m, String id) {
        if (!isQualcomm(m, id)) return null;
        File dir = new File(m.getDriverDir(id), LAYER_DIR);
        return dir.isDirectory() ? dir : null;
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    /**
     * Download, verify and install. Returns the driver id.
     * @throws IllegalArgumentException with a user-facing reason (checksum mismatch, bad package)
     * @throws IOException on network or disk failures
     */
    public static String install(Context context, Progress progress) throws IOException {
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        if (m.isInstalled(ID)) return ID;
        File cache = new File(context.getCacheDir(), "qualcomm-kgsl.pkg.tar.zst");
        File tmp = new File(m.getDriverDir(ID).getParentFile(), ".tmp-qcom-" + System.currentTimeMillis());
        boolean keep = false;
        try {
            File unpacked = new File(tmp, "pkg");
            ValvePackage.fetch(URL_STRING, SHA256, SIZE, cache, unpacked,
                    progress == null ? null : progress::onProgress);

            File srcLibs = new File(unpacked, "usr/lib/adreno");
            File so = new File(srcLibs, LIB_NAME);
            if (!so.isFile() || !LinuxVulkanDriverManager.isAarch64Elf(so)) {
                throw new IllegalArgumentException("The package has no AArch64 " + LIB_NAME + ".");
            }
            File dir = new File(tmp, "out");
            if (!dir.mkdirs() || !srcLibs.renameTo(new File(dir, ADRENO_DIR))) {
                throw new IOException("could not place the driver's libraries");
            }
            fixSearchPath(new File(dir, ADRENO_DIR));
            // The layers' manifests already name /usr/lib/adreno/wsi_*.so, the bound path.
            File srcLayers = new File(unpacked, "usr/share/vulkan/implicit_layer.d");
            File layers = new File(dir, LAYER_DIR);
            if (!layers.mkdirs() || !moveChildren(srcLayers, layers)) {
                throw new IOException("could not place the driver's surface layers");
            }

            JSONObject icd = new JSONObject();
            icd.put("file_format_version", "1.0.0");
            JSONObject body = new JSONObject();
            body.put("library_path", GUEST_DIR + "/" + LIB_NAME);
            body.put("api_version", "1.3.237");
            icd.put("ICD", body);
            if (!FileUtils.writeString(new File(dir, LinuxVulkanDriverManager.ICD_NAME), icd.toString(2))) {
                throw new IOException("cannot write icd.json");
            }
            JSONObject meta = new JSONObject();
            meta.put("schemaVersion", 1);
            meta.put("kind", "linux-vulkan-icd");
            meta.put("flavor", FLAVOR);
            meta.put("name", NAME);
            meta.put("driverVersion", VERSION);
            meta.put("libc", "glibc");
            meta.put("minGlibc", "2.34");
            meta.put("source", URL_STRING);
            meta.put("sha256", SHA256);
            meta.put("importedAt", System.currentTimeMillis());
            if (!FileUtils.writeString(new File(dir, LinuxVulkanDriverManager.META_NAME), meta.toString(2))) {
                throw new IOException("cannot write meta.json");
            }
            if (!dir.renameTo(m.getDriverDir(ID))) throw new IOException("cannot move into " + m.getDriverDir(ID));
            keep = true;
            Log.i(TAG, "installed " + ID + " -> " + m.getDriverDir(ID));
            return ID;
        } catch (org.json.JSONException e) {
            throw new IOException("manifest write failed: " + e.getMessage());
        } finally {
            FileUtils.delete(tmp);
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
            if (!keep) Log.w(TAG, "install of " + ID + " did not complete");
        }
    }

    /**
     * The search path Valve's build of {@code libvulkan_adreno.so} carries -
     * {@code $ORIGIN/../../llvm-glnext:$ORIGIN/../../adreno_utils:$ORIGIN/../../gsl:} - names
     * folders of their build tree, not /usr/lib/adreno where its companions sit, so six of them are
     * "not found" and the Vulkan loader drops the driver (gamescope: vkCreateInstance -9). On the
     * Frame an ld.so.conf entry covers it; here the runtime is not ours to change, so the DT_RPATH
     * string is overwritten in place with {@code $ORIGIN} (NUL-padded to the same length): the
     * libraries beside it. Proven on the Pocket FIT: 0 missing, vulkaninfo lists the device.
     * Idempotent - a patched or different build is left alone.
     */
    public static void fixSearchPath(File adrenoDir) {
        File so = new File(adrenoDir, LIB_NAME);
        try {
            byte[] b = java.nio.file.Files.readAllBytes(so.toPath());
            byte[] old = OLD_RPATH.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            int at = indexOf(b, old);
            if (at < 0) return;
            byte[] repl = "$ORIGIN".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(repl, 0, b, at, repl.length);
            java.util.Arrays.fill(b, at + repl.length, at + old.length, (byte) 0);
            File staged = new File(adrenoDir, LIB_NAME + ".staged");
            java.nio.file.Files.write(staged.toPath(), b);
            if (!staged.renameTo(so)) {
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
                Log.e(TAG, "could not replace " + so + " with its patched copy");
                return;
            }
            Log.i(TAG, "search path of " + so + " set to $ORIGIN");
        } catch (IOException e) {
            Log.e(TAG, "could not patch " + so, e);
        }
    }

    private static final String OLD_RPATH = "$ORIGIN/../../llvm-glnext:$ORIGIN/../../adreno_utils:$ORIGIN/../../gsl:";

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static boolean moveChildren(File from, File to) {
        if (!from.isDirectory()) return false;
        File[] kids = from.listFiles();
        if (kids == null) return false;
        for (File k : kids) if (!k.renameTo(new File(to, k.getName()))) return false;
        return true;
    }
}
