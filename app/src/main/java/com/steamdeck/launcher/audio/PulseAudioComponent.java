package com.steamdeck.launcher.audio;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.steamdeck.launcher.core.FileUtils;
import com.steamdeck.launcher.core.SessionPart;
import com.steamdeck.launcher.core.HostProcess;
import com.steamdeck.launcher.core.TarZst;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

/**
 * PulseAudio 13.0 running in the app process, with Android's AAudio as its sink, so the guest's
 * libpulse clients (Steam and everything it launches) have a server to talk to. The socket lives
 * in the app's files directory and is bound into the session at its own path, so PULSE_SERVER
 * needs no translating.
 *
 * <p>Ported down from Bannerlator's component: no sink suspend/resume and no route-change
 * recreate — those need the pasink native client, and a session here is a foreground activity
 * that does not background the way a game container does.
 */
public class PulseAudioComponent extends SessionPart {
    private static final String TAG = "PulseAudio";
    /** Where the guest reaches the daemon; the session exports PULSE_SERVER=unix:<this>. */
    public static final String SOCKET_NAME = "PS0";
    /** Identifies the bundled pulseaudio.tzst; a change here re-unpacks it over what a device has. */
    private static final String BUNDLE_STAMP = "2026-09-23-pa13-relay-sink-r3";

    private final File workingDir;
    /** Where the daemon's own output is kept for this session, or null for logcat only. */
    private File logFile;
    /**
     * A named pipe carrying microphone audio, or null for no microphone. The bundle ships
     * module-aaudio-sink but no matching source, and the daemon runs where Android permits
     * recording - so pointing module-pipe-source at a pipe the DirectAudio relay helper writes
     * turns that one stream into a source the client can see, named DirectAudioMic.
     */
    private final String micFifoPath;
    /** The DirectAudio relay's socket when the client's output should go through it, else null. */
    private String relaySocketPath;
    private int pid = -1;

    public PulseAudioComponent(Context context) {
        this(context, null);
    }

    /** As above, with a microphone fed from {@code micFifoPath}; null for output only. */
    public PulseAudioComponent(Context context, String micFifoPath) {
        this.workingDir = new File(context.getFilesDir(), "pulseaudio");
        this.micFifoPath = micFifoPath;
    }

    /**
     * Route the daemon's output through the DirectAudio relay at this socket instead of an AAudio
     * stream of its own. The relay owns the stream outside proot, with its adaptive buffer; the
     * daemon only fills a shared ring. Set before {@link #start()}; the relay may start later.
     */
    public void setRelaySocket(String path) {
        this.relaySocketPath = path;
    }

    /** Send the daemon's output to this file as well as logcat. Set before {@link #start()}. */
    public void setLogFile(File file) {
        this.logFile = file;
    }

    public File socket() {
        return new File(workingDir, SOCKET_NAME);
    }

    @Override
    public void start() {
        stop();
        if (!workingDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }
        // The loadable modules (module-aaudio-sink - the app's own, from tools/aaudio-sink - the
        // native protocol, the pipe modules) ride in the apk; the daemon and its libraries come from the native library directory, the one
        // place an app may execute a file from. The bundle is unpacked once per BUNDLE_STAMP, not
        // once ever: an installed app kept the modules it unpacked on its first run, so a bundle
        // fixed in a later build never reached the device - which is how a 17.0 glibc build of
        // module-pipe-source sat beside the 13.0 daemon, failed to dlopen, and the microphone
        // never appeared. Bump the stamp whenever pulseaudio.tzst changes.
        File modulesDir = new File(workingDir, "modules");
        File stamp = new File(modulesDir, ".bundle");
        String have = FileUtils.readString(stamp);
        if (!new File(modulesDir, "arm64/module-aaudio-sink.so").isFile()
                || have == null || !BUNDLE_STAMP.equals(have.trim())) {
            Log.i(TAG, "unpacking pulseaudio.tzst (" + BUNDLE_STAMP + "; had " + have + ")");
            FileUtils.delete(modulesDir);
            if (TarZst.extractAsset(app(), "pulseaudio.tzst", workingDir)) {
                FileUtils.writeString(stamp, BUNDLE_STAMP);
            } else {
                Log.e(TAG, "pulseaudio.tzst did not unpack");
            }
        }
        copyFromLibraryDir();

        //noinspection ResultOfMethodCallIgnored
        socket().delete();
        // module-pipe-source creates the pipe with mkfifo and fails outright if one is already
        // there - EEXIST, reported as "Unknown error 17" - and the module then does not load at
        // all, so the source never appears and the client reports no microphone. Ours lives in the
        // app's files directory and survives a session, so after the very first run the path would
        // always be occupied. Removed here, before the daemon reads this config: the daemon makes
        // it, and the relay helper starts afterwards and is content to find one already made.
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(micFifoPath).delete();
        }
        ArrayList<String> config = new ArrayList<>();
        config.add("load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""
                + socket().getAbsolutePath() + "\"");
        // volume=1.0 is not optional: with no volume argument module-aaudio-sink defaults
        // the sink to 0% and the session plays silence.
        if (relaySocketPath != null && !relaySocketPath.isEmpty()) {
            config.add("load-module module-directaudio-sink socket=\"" + relaySocketPath + "\" performance_mode=1 adaptive=1 volume=1.0");
        } else {
            config.add("load-module module-aaudio-sink performance_mode=1 adaptive=1 volume=1.0");
        }
        config.add("set-default-sink AAudioSink");
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            // The format is the helper's, fixed at s16le/48000/mono: it resamples when the device
            // grants another input rate, so the daemon is never told a rate the bytes are not.
            // A pipe has no clock, so nothing here corrects drift - acceptable for voice.
            config.add("load-module module-pipe-source source_name=DirectAudioMic file=\""
                    + micFifoPath + "\" format=s16le rate=48000 channels=1");
            config.add("set-default-source DirectAudioMic");
        }
        FileUtils.writeString(new File(workingDir, "default.pa"), String.join("\n", config));

        File modules = new File(workingDir, "modules/arm64");
        ArrayList<String> env = new ArrayList<>();
        env.add("LD_LIBRARY_PATH=/system/lib64:" + modules + ":" + workingDir.getAbsolutePath());
        env.add("HOME=" + workingDir);
        env.add("TMPDIR=" + workingDir);

        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so"
                + " --system=false --disable-shm=true --fail=false"
                + " -n --file=default.pa --daemonize=false --use-pid-file=false --exit-idle-time=-1";
        // A module that refuses to load, a pipe that could not be made, the daemon exiting at
        // startup: all of it used to reach logcat and nothing else, so a user's folder said nothing
        // at all about sound. Four separate faults hid behind "no input device" in one night.
        final java.io.PrintWriter out = openLog();
        pid = HostProcess.start(command, env.toArray(new String[0]), workingDir, null,
                line -> {
                    Log.i(TAG, line);
                    if (out != null) synchronized (out) { out.println(line); out.flush(); }
                });
    }

    @Override
    public void stop() {
        if (pid != -1) {
            Process.killProcess(pid);
            pid = -1;
        }
    }

    /** The session's audio log, appended to by both the daemon and the relay helper. */
    private java.io.PrintWriter openLog() {
        if (logFile == null) return null;
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(logFile, true));
            w.println("== PulseAudio daemon starting" + (micFifoPath != null ? " with a microphone source" : ""));
            w.flush();
            return w;
        } catch (Exception e) {
            Log.w(TAG, "could not open " + logFile, e);
            return null;
        }
    }

    private void copyFromLibraryDir() {
        String[] libs = {"libltdl.so", "libpulseaudio.so", "libpulse.so",
                "libpulsecommon-13.0.so", "libpulsecore-13.0.so", "libsndfile.so", "libffi.so"};
        ClassLoader loader = PulseAudioComponent.class.getClassLoader();
        for (String lib : libs) {
            URL resource = loader != null ? loader.getResource("lib/arm64-v8a/" + lib) : null;
            if (resource == null) {
                Log.w(TAG, lib + " missing from the apk");
                continue;
            }
            File destination = new File(workingDir, lib);
            try (InputStream in = resource.openStream()) {
                Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                FileUtils.chmod(destination, 0771);
            } catch (Exception e) {
                Log.w(TAG, "copy " + lib, e);
            }
        }
    }
}
