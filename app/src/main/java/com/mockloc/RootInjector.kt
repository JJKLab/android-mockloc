package com.mockloc

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tier-2 injection driven from inside the App, replacing the "wait for the root module's mtime
 * loop or plug in a PC" hop (REFACTOR-PLAN 1.3 / 4.2). KernelSU grants su to *apps* independently
 * of what it gives `adb shell` (which this ROM strips), so when the user has approved us once, the
 * App can:
 *  1. publish the rendered conf straight to /data/adb + /data/local/tmp (the module and the
 *     mtime-based `sync_app_prefs` then see the App as the newest writer and yield), and
 *  2. run the same `cmd location` commands the boot script runs, for an immediate effect.
 *
 * One-shot providers still die with the shell that registered them (A10), so this is always paired
 * with publishConfig(): with the module flashed the boot loop takes over maintenance; without it,
 * the user re-injects from the App.
 */
object RootInjector {

    private const val TIMEOUT_MS = 8000L
    private val CFG_DIR = "/data/adb/mockloc"
    private val CFG_PATH = "$CFG_DIR/config.prop"
    private val PUB_PATH = "/data/local/tmp/mockloc.conf"

    @Volatile
    private var probeCache: Boolean? = null

    /** Cached su probe; [force] re-runs it (the user may have granted us between probes). */
    fun rootAvailable(force: Boolean = false): Boolean {
        if (!force) probeCache?.let { return it }
        val out = runSu(listOf("id")).getOrElse { return false.also { probeCache = false } }
        val ok = out.contains("uid=0")
        probeCache = ok
        return ok
    }

    /**
     * Feed commands to an interactive su shell over stdin: no quoting layers, one process for the
     * whole batch. Returns combined stdout/stderr; a nonzero exit is reported by the caller via
     * the output itself, because `cmd location` failures print to stderr while su exits 0.
     */
    fun runSu(commands: List<String>): Result<String> = runCatching {
        val process = ProcessBuilder("su").start()
        process.outputStream.use { os ->
            commands.forEach { cmd -> os.write((cmd + "\n").toByteArray()) }
            os.write("exit\n".toByteArray())
            os.flush()
        }
        val out = process.inputStream.use { it.readBytes().decodeToString() }
        val err = process.errorStream.use { it.readBytes().decodeToString() }
        if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("su timed out after ${TIMEOUT_MS}ms")
        }
        (out + err).ifBlank { "(no output, exit=${process.exitValue()})" }
    }

    /**
     * Write the conf the way the boot module publishes it: the private copy under /data/adb
     * (root-only) and the world-readable mirror the Xposed module reads. The rendered body comes
     * from [ModuleBridge.render] verbatim - one renderer, no reformatting here.
     */
    fun publishConfig(context: Context, body: String): Boolean {
        val staging = File(context.cacheDir, "mockloc-publish.conf").apply { writeText(body) }
        val out = runSu(
            listOf(
                "mkdir -p $CFG_DIR",
                "cat ${staging.absolutePath} > $CFG_PATH",
                "cp ${staging.absolutePath} $PUB_PATH",
                "chmod 600 $CFG_PATH",
                "chmod 664 $PUB_PATH",
                "rm -f ${staging.absolutePath}",
            ),
        ).getOrElse { return false }
        // The mirror must parse as enabled for the module to act; a truncated cat shows up here.
        return out.contains("Permission denied").not()
    }

    /**
     * The exact command set the boot script runs (A15 tier-2 shape): app-op for uid 0, master
     * switch on, one test provider per name, each fed immediately. Returns the su output so the
     * caller can surface failures on the status page instead of guessing.
     */
    fun apply(lat: Double, lng: Double, acc: Float, providers: List<String>, alt: Double): String {
        val stamp = System.currentTimeMillis()
        val cmds = mutableListOf(
            "appops set 0 android:mock_location allow",
            "cmd location set-location-enabled true",
        )
        providers.forEach { p ->
            cmds += "cmd location providers add-test-provider $p"
            cmds += "cmd location providers set-test-provider-enabled $p true"
            cmds += ("cmd location providers set-test-provider-location $p " +
                "--location $lat,$lng --accuracy $acc --time $stamp")
        }
        return runSu(cmds).getOrElse { "su failed: ${it.message}" }
    }

    fun teardown(providers: List<String>): String = runSu(
        providers.flatMap { p ->
            listOf(
                "cmd location providers set-test-provider-enabled $p false",
                "cmd location providers remove-test-provider $p",
            )
        },
    ).getOrElse { "su failed: ${it.message}" }

    /** Nothing to reclaim yet - called when the App's pick stops being global. */
    fun clearGlobalFlag(context: Context): Boolean =
        runSu(listOf("sed -i 's/^MOCKLOC_GLOBAL=.*/MOCKLOC_GLOBAL=false/' $CFG_PATH $PUB_PATH"))
            .isSuccess
}
