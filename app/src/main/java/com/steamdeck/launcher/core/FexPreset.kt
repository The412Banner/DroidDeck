package com.steamdeck.launcher.core

/**
 * FEXCore presets for the x86 games the Steam client launches, Bannerlator's table carried over
 * so a preset means the same thing in both apps. Each is a set of FEX_* variables put into the
 * session's environment; the games under FEX inherit them from the client. The default is FEX's
 * own defaults - no variables at all - which is what every session has run on so far.
 *
 * TSO = x86's total store ordering emulated on ARM: safest, slowest. MULTIBLOCK compiles more per
 * block. X87REDUCEDPRECISION trades x87 exactness for speed. SMCCHECKS=none is the risky one -
 * anything that writes its own code at runtime (JIT, .NET, DRM) can misbehave. DENUVO keeps the
 * checks at full and hides the hypervisor bit, which its anti-tamper looks for.
 */
object FexPreset {
    class Preset(val id: String, val label: String, val detail: String, val env: List<String>)

    private fun tso(main: Int, vector: Int, memcpy: Int, halfBarrier: Int) = listOf(
        "FEX_TSOENABLED=$main", "FEX_VECTORTSOENABLED=$vector", "FEX_MEMCPYSETTSOENABLED=$memcpy", "FEX_HALFBARRIERTSOENABLED=$halfBarrier",
    )

    val all: List<Preset> = listOf(
        Preset("", "FEX defaults", "No variables set; what every session ran on until now.", emptyList()),
        Preset("STABILITY", "Stability", "Every TSO emulation on, single-block: the slowest and the surest.", tso(1, 1, 1, 1) + listOf("FEX_X87REDUCEDPRECISION=0", "FEX_MULTIBLOCK=0")),
        Preset("COMPATIBILITY", "Compatibility", "Every TSO emulation on, multiblock.", tso(1, 1, 1, 1) + listOf("FEX_X87REDUCEDPRECISION=0", "FEX_MULTIBLOCK=1")),
        Preset("INTERMEDIATE", "Intermediate", "Main and half-barrier TSO on, vector and memcpy off, reduced x87.", tso(1, 0, 0, 1) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1")),
        Preset("PERFORMANCE", "Performance", "All TSO off, reduced x87, multiblock.", tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1")),
        Preset("PERFORMANCE_TSO", "Performance + TSO", "Performance with the main store ordering kept on, for the many games that need it.", tso(1, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1")),
        Preset("EXTREME", "Extreme", "Performance plus no self-modifying-code checks and FEX's block caches retuned. JIT-heavy titles and DRM can break.",
            tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMCCHECKS=none", "FEX_DISABLEL2CACHE=1", "FEX_DYNAMICL1CACHE=1", "FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC=250", "FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC=50")),
        Preset("EXTREME_TSO", "Extreme + TSO", "Extreme with the main store ordering kept on.",
            tso(1, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMCCHECKS=none", "FEX_DISABLEL2CACHE=1", "FEX_DYNAMICL1CACHE=1", "FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC=250", "FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC=50")),
        Preset("EXTREME_GN", "Extreme-gn", "Performance plus the small TSC scale and volatile-metadata hints; SMC checks stay on. Try before Extreme.",
            tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMALLTSCSCALE=1", "FEX_VOLATILEMETADATA=1")),
        Preset("DENUVO", "Denuvo", "Performance with full SMC checks and the hypervisor bit hidden, for titles whose anti-tamper looks for either.",
            tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMCCHECKS=full", "FEX_HIDEHYPERVISORBIT=1")),
    )

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all[0]

    /** The variables for the session's environment, in KEY=VALUE form; empty for the default. */
    fun env(id: String): List<String> = byId(id).env
}
