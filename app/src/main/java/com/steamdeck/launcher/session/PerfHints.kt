package com.steamdeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.steamdeck.launcher.wayland.WaylandCompositor
import java.util.concurrent.Executors

/**
 * ADPF (Android 12+): a PerformanceHintManager session over the compositor thread, told the
 * panel's frame period as its target and each presented frame's interval as the actual. When a
 * frame runs long the power HAL raises CPU clocks for the next one at once, instead of the
 * governor waiting for the load average to climb — the difference between a menu that stutters
 * on the first scroll and one that does not.
 *
 * The session may only carry this process's threads, so the guest (gamescope, the Steam client
 * under FEX) cannot join it; they are covered by the app-wide game category and sustained mode.
 * Below API 31 nothing here runs; on a power HAL without hint sessions createHintSession returns
 * null and the log says so.
 */
object PerfHints {
    private const val TAG = "PerfHints"

    @Volatile private var session: PerformanceHintManager.Session? = null
    @Volatile private var target = 0L
    /** Compositor thread only. */
    private var last = 0L
    private val main = Handler(Looper.getMainLooper())
    /** Reports leave the compositor thread: each one is a binder call to the power HAL. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "adpf-report").apply { isDaemon = true } }

    /** Opens the session once the compositor thread exists (it is started just before this). */
    fun arm(context: Context, refreshHz: Float) {
        if (Build.VERSION.SDK_INT < 31) { Log.i(TAG, "adpf: needs Android 12; this is API ${Build.VERSION.SDK_INT}"); return }
        val wanted = (1_000_000_000.0 / refreshHz.coerceAtLeast(30f)).toLong()
        val s = session
        if (s != null) {
            if (wanted != target) { target = wanted; try { s.updateTargetWorkDuration(wanted) } catch (t: Throwable) { Log.w(TAG, "adpf: retarget", t) } }
            return
        }
        target = wanted
        val app = context.applicationContext
        var tries = 0
        main.post(object : Runnable {
            override fun run() {
                if (session != null) return
                val tid = try { WaylandCompositor.nativeCompositorTid() } catch (t: Throwable) { 0 }
                if (tid == 0) {
                    if (++tries < 30) main.postDelayed(this, 100) else Log.w(TAG, "adpf: the compositor thread never reported its tid")
                    return
                }
                open(app, tid)
            }
        })
    }

    @RequiresApi(31)
    private fun open(app: Context, tid: Int) {
        val pm = app.getSystemService(PerformanceHintManager::class.java)
        if (pm == null) { Log.i(TAG, "adpf: no PerformanceHintManager on this device"); return }
        val s = try { pm.createHintSession(intArrayOf(tid), target) } catch (t: Throwable) { Log.w(TAG, "adpf: createHintSession", t); null }
        if (s == null) { Log.i(TAG, "adpf: unsupported here (the power HAL has no hint sessions)"); return }
        last = 0L
        session = s
        Log.i(TAG, "adpf: session on compositor thread $tid · target ${target / 1000} µs · HAL wants updates every ${pm.preferredUpdateRateNanos / 1000} µs")
    }

    /** Compositor thread, once per presented game frame; the interval since the last is the actual. */
    @JvmStatic
    fun onFrame() {
        val s = session ?: return
        val now = System.nanoTime()
        val prev = last
        last = now
        if (prev == 0L) return
        val dt = now - prev
        // Under half a millisecond is a double present; over half a second is a pause, not a frame.
        if (dt < 500_000L || dt > 500_000_000L) return
        worker.execute {
            try { s.reportActualWorkDuration(dt) } catch (t: Throwable) { Log.w(TAG, "adpf: report failed, session dropped", t); session = null }
        }
    }
}
