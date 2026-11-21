package com.airdrop.android.daemon

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object RadioInstaller {
    private const val TAG = "AirDrop"

    private const val DIR = "/data/adb/o13"
    private const val START_LOG = "/data/local/tmp/airdrop-start.log"

    private const val LEGACY_MODULE = "/data/adb/modules/owly"


    private const val PREFS = "airdrop"
    private const val KEY_OURS = "radio_installed_by_app"
    private const val KEY_SHARE_ORIG = "share_state_original"

    /** The only models the bundled MT6991 radio stack may be installed on. */
    private val MT6991_MODELS = setOf("OPD2508", "PKT110", "PLB110")

    fun isMt6991(model: String = android.os.Build.MODEL): Boolean =
        model.uppercase(java.util.Locale.ROOT) in MT6991_MODELS

    internal fun assetForModel(model: String): String? = if (isMt6991(model)) "radio-mt6991.zip" else null

    fun hasRoot(): Boolean = su("id", 20).contains("uid=0")

    data class Lsposed(val dbFound: Boolean, val known: Boolean, val enabled: Boolean, val scoped: Boolean)

    fun lsposed(ctx: Context): Lsposed {
        val out = su(
            "CLASSPATH=${ctx.applicationInfo.sourceDir} app_process /system/bin " +
                "com.airdrop.android.daemon.LsposedSetup query ${ctx.packageName} ${Ipc.SHARE_PKG} ${ctx.applicationInfo.sourceDir} 2>&1",
            30,
        )
        if (out.contains("nodb")) return Lsposed(false, false, false, false)
        val line = out.lines().lastOrNull { it.startsWith("known=") } ?: return Lsposed(true, false, false, false)
        return Lsposed(
            true,
            line.contains("known=true"),
            line.contains("enabled=true"),
            line.contains("scoped=true"),
        )
    }

    /**
     * Enables the module in LSPosed and scopes it to the share app. LSPosed keeps its settings in memory, so
     * the module's package is then marked changed, which makes LSPosed read them again; failing that, a
     * reboot applies them. Returns whether the database now says enabled and scoped.
     */
    fun grantLsposed(ctx: Context): Boolean {
        val apk = ctx.applicationInfo.sourceDir
        val out = su(
            "CLASSPATH=$apk app_process /system/bin " +
                "com.airdrop.android.daemon.LsposedSetup grant ${ctx.packageName} ${Ipc.SHARE_PKG} $apk 2>&1",
            30,
        )
        Log.i(TAG, "LSPosed grant: ${out.trim().lines().lastOrNull().orEmpty()}")
        if (!out.contains("granted")) return false
        runCatching {
            val c = android.content.ComponentName(ctx, com.airdrop.android.BootReceiver::class.java)
            val pm = ctx.packageManager
            val keep = android.content.pm.PackageManager.DONT_KILL_APP
            pm.setComponentEnabledSetting(c, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, keep)
            pm.setComponentEnabledSetting(c, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, keep)
        }.onFailure { Log.w(TAG, "could not nudge LSPosed: $it") }
        return true
    }

    /** The last part of the daemon log, enough for a few minutes of STATS lines. */
    fun daemonLogTail(): String = su("tail -c 60000 /data/adb/owly/daemon.log 2>/dev/null", 10)

    /** Restarts the AirDrop session the way turning AirDrop off and on does: share mode 1 -> 0 -> 1. */
    fun cycleShareState() {
        suDetached("settings put system oshare_state 0; sleep 1; settings put system oshare_state 1")
    }

    /** True while an install or reinstall is running; nothing else may start the stack then. */
    @Volatile var installing = false
        private set

    /**
     * Whether any part of the stack is running: the daemon or its watchdog. Either one means a start is
     * under way or the stack is being looked after, and the service script would kill a running daemon.
     */
    fun stackAlive(): Boolean = su(
        "pidof owly >/dev/null && echo ALIVE; kill -0 \"\$(cat $WATCH_PID 2>/dev/null)\" 2>/dev/null && echo ALIVE",
        10,
    ).contains("ALIVE")

    private fun restoreShareState(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SHARE_ORIG, 0).coerceAtLeast(0)

    @Synchronized
    fun uninstall(ctx: Context): String? {
        if (!hasRoot()) return "no root"
        val script = File(ctx.cacheDir, "airdrop-uninstall.sh")
        return try {
            ctx.assets.open("radio-remove.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
            // Probe namespace access first; never repeat a partially executed removal on failure.
            val out = su(
                "if nsenter --mount=/proc/1/ns/mnt -- true 2>/dev/null; then " +
                    "nsenter --mount=/proc/1/ns/mnt -- sh '${script.path}' 2>&1; else sh '${script.path}' 2>&1; fi", 60,
            )
            if (out.lines().any { it.trim() == "SKY_REMOVED" }) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_OURS, false).remove(KEY_SHARE_ORIG)
                    .putBoolean("radio_installed", false).putBoolean("airdrop_on", false).apply()
                null
            } else out.trim().lines().lastOrNull().orEmpty().ifBlank { "unknown" }
        } finally {
            script.delete()
        }
    }

    @Synchronized
    fun install(ctx: Context): Failure? {
        installing = true
        try { return installLocked(ctx) } finally { installing = false }
    }

    private fun installLocked(ctx: Context): Failure? {
        val asset = assetForModel(android.os.Build.MODEL) ?: return Failure.UnsupportedDevice
        if (!hasRoot()) return Failure.NoRoot
        if (su("[ -d $LEGACY_MODULE ] && echo PRESENT", 20).contains("PRESENT")) return Failure.ExistingModule
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SHARE_ORIG)) {
            val now = runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, "oshare_state", 0) }.getOrDefault(0)
            prefs.edit().putInt(KEY_SHARE_ORIG, now.coerceAtLeast(0)).apply()
        }
        val zip = File(ctx.cacheDir, "radio.zip")
        val boot = File(ctx.cacheDir, "airdrop-radio.sh")
        val script = File(ctx.cacheDir, "airdrop-install.sh")
        return try {
            ctx.assets.open(asset).use { input -> zip.outputStream().use { input.copyTo(it) } }
            ctx.assets.open("radio-install.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
            boot.writeText("#!/system/bin/sh\n[ -f $DIR/service.sh ] && cd $DIR && exec sh $DIR/service.sh\n")
            val country = java.util.Locale.getDefault().country.uppercase(java.util.Locale.ROOT)
                .takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "00"
            val owned = if (ours(ctx)) 1 else 0
            val out = su("sh '${script.path}' '${zip.path}' '${boot.path}' '$country' ${restoreShareState(ctx)} $owned 2>&1", 180)
            if (!out.lines().any { it.trim() == "SKY_OK" }) {
                Failure.Unpack(out.trim().takeLast(500))
            } else {
                prefs.edit().putBoolean(KEY_OURS, true).apply()
                start(enableAirDrop = true)
                null
            }
        } catch (e: Exception) {
            Failure.Unpack(e.message ?: "Cannot prepare radio installation")
        } finally {
            script.delete()
            boot.delete()
            zip.delete()
        }
    }

    fun ours(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OURS, false)

    /**
     * Asks the radio watchdog for a full rebuild on its next pass. Used when the user turns AirDrop back on:
     * that is what people do when the device has stopped showing up, and a stack that only looks healthy
     * would otherwise be left as it is.
     */
    fun requestRestart() {
        suDetached("[ -d /data/adb/owly ] && touch /data/adb/owly/restart-request")
    }

    fun setAirDropMode(on: Boolean) {
        suDetached("settings put system oshare_state ${if (on) 1 else 0}")
    }

    fun provisionToken(ctx: Context, token: String) {
        if (!token.matches(Regex("[0-9a-f]{16,}"))) return
        val script = File(ctx.cacheDir, "bridge-token.sh")
        try {
            script.writeText(
                listOf(
                    "T=\$1",
                    "P=${Ipc.SHARE_PKG}",
                    "[ -d /data/user/0/\$P ] || exit 0",
                    "D=/data/user/0/\$P/files",
                    "[ \"\$(cat \$D/${Ipc.TOKEN_FILE} 2>/dev/null)\" = \"\$T\" ] && exit 0",
                    "mkdir -p \$D; U=\$(stat -c %u /data/user/0/\$P)",
                    "printf '%s' \"\$T\" > \$D/${Ipc.TOKEN_FILE}",
                    "chown \$U:\$U \$D \$D/${Ipc.TOKEN_FILE}; chmod 700 \$D; chmod 600 \$D/${Ipc.TOKEN_FILE}",
                    "restorecon -R \$D 2>/dev/null; echo provisioned",
                ).joinToString("\n", postfix = "\n"),
            )
            val out = su("nsenter --mount=/proc/1/ns/mnt -- sh ${script.path} $token 2>&1 || sh ${script.path} $token", 20)
            Log.i(TAG, "bridge token: ${out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "unchanged"}")
        } finally {
            script.delete()
        }
    }

    fun setWifi(on: Boolean) {
        suDetached("svc wifi ${if (on) "enable" else "disable"}")
    }

    private const val WATCH_PID = "/data/adb/owly/stack/watch.pid"

    /** Runs the service script unless both the daemon and its watchdog are alive; the script itself is idempotent. */
    private const val START_STACK =
        "[ -f $DIR/service.sh ] || exit 0; " +
            "ps -A | grep -q ' owly\$' && kill -0 \"\$(cat $WATCH_PID 2>/dev/null)\" 2>/dev/null || " +
            "(cd $DIR && setsid sh $DIR/service.sh </dev/null >$START_LOG 2>&1 &)"

    @Synchronized
    fun start(enableAirDrop: Boolean = false) {
        val on = if (enableAirDrop) "[ \"\$(settings get system oshare_state)\" = 1 ] || settings put system oshare_state 1; " else ""
        suDetached(on + START_STACK)
    }

    /** Restarts the watchdog if it died while the daemon kept running; nothing else looks after the radio then. */
    @Synchronized
    fun ensureSupervised() = suDetached(START_STACK)

    /**
     * Asks the system not to restrict this app in the background: without it the foreground service cannot be
     * started from a broadcast, and doze defers the events the share app relays.
     */
    fun protectApp(pkg: String) {
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) return
        suDetached(
            "cmd deviceidle whitelist +$pkg; cmd appops set $pkg RUN_IN_BACKGROUND allow; " +
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; am set-standby-bucket $pkg active",
        )
    }

    /**
     * Launches the share app's process if it is not running, without showing anything: a content-provider
     * lookup from root starts the process, which loads the module. Returns whether it is running afterwards.
     */
    fun wakeShareApp(): Boolean = su(
        "content query --uri content://${Ipc.SHARE_PKG}.androidx-startup/airdrop >/dev/null 2>&1; pidof ${Ipc.SHARE_PKG}",
        15,
    ).trim().lines().firstOrNull()?.trim()?.isNotEmpty() == true

    /** Restarts the share app so a module enabled after it started gets loaded. */
    fun restartShareApp(): Boolean {
        su("am force-stop ${Ipc.SHARE_PKG}", 15)
        return wakeShareApp()
    }

    @Volatile private var bundled: String? = null

    /** The radio version inside this APK; a newer APK does not update the installed radio by itself. */
    fun bundledVersion(ctx: Context): String? = bundled ?: runCatching {
        val asset = assetForModel(android.os.Build.MODEL) ?: return null
        java.util.zip.ZipInputStream(ctx.assets.open(asset)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "module.prop" } ?: return null
            zip.bufferedReader().lineSequence().firstOrNull { it.startsWith("version=") }?.removePrefix("version=")?.trim()
        }
    }.getOrNull()?.also { bundled = it }

    fun installedVersion(): String? =
        su("grep ^version= $DIR/module.prop 2>/dev/null", 20).trim().removePrefix("version=").ifEmpty { null }

    fun log(): String = su(
        "echo '== su'; id; echo; " +
            "echo '== process'; ps -A | grep -E ' owly\$'; echo; " +
            "echo '== socket'; grep -a owly-android /proc/net/unix | head -3; echo; " +
            "for p in \$(pidof owly); do echo \"== owly \$p\"; grep -E '^(State|VmRSS)' /proc/\$p/status; " +
            "cat /proc/\$p/cgroup; echo \"oom_score_adj \$(cat /proc/\$p/oom_score_adj)\"; done; echo; " +
            "echo '== share mode'; settings get system oshare_state; echo; " +
            "echo '== watchdog process'; w=\$(cat /data/adb/owly/stack/watch.pid 2>/dev/null); " +
            "if [ -n \"\$w\" ] && kill -0 \"\$w\" 2>/dev/null; then echo \"running \$w\"; cat /proc/\$w/cgroup; " +
            "else echo \"NOT RUNNING (pid file: \${w:-none})\"; fi; " +
            "echo \"wifi \$(iw dev wlan0 link 2>/dev/null | grep -E 'freq|signal' | tr '\n' ' ')\"; " +
            "echo \"policy-5ghz \$([ -f /data/adb/owly/policy-5ghz ] && echo yes || echo no) coex-on \$([ -f /data/adb/owly/coex-on ] && echo yes || echo no)\"; echo; " +
            "echo '== daemon (last stats and events)'; t=\$(tail -c 200000 /data/adb/owly/daemon.log 2>/dev/null); " +
            "printf '%s\\n' \"\$t\" | grep -a STATS | tail -n 3; printf '%s\\n' \"\$t\" | grep -a -v STATS | tail -n 40; echo; " +
            "echo '== watchdog'; tail -n 25 /data/adb/owly/stack/watch.log; echo; " +
            "echo '== rate'; tail -n 8 /data/adb/owly/stack/speed-watch.log; echo; " +
            "echo '== files'; ls -la $DIR 2>&1 | head -12; echo; " +
            "for f in $START_LOG /data/adb/owly/stderr.log /data/adb/owly/stack/stack.log; do " +
            "echo \"== \$f\"; tail -n 30 \$f 2>/dev/null; echo; done",
        30,
    ).ifBlank { "(no output — su did not answer; is root granted to AirDrop in KernelSU?)" }

    sealed interface Failure {
        data object NoRoot : Failure
        data object UnsupportedDevice : Failure
        data object ExistingModule : Failure
        data class Unpack(val detail: String) : Failure
    }

    fun viewAsRoot(uri: String, mime: String): Boolean {
        val quoted = uri.replace("'", "'\\''")
        val out = su("am start -a android.intent.action.VIEW -d '$quoted' -t '$mime'", 20)
        Log.i(TAG, "opening as root: $uri ($mime) -> ${out.trim().lines().firstOrNull().orEmpty()}")
        return out.contains("Starting:")
    }

    fun showFolderAsRoot(): Boolean {
        val out = su(
            "am start -n com.android.documentsui/com.android.documentsui.files.FilesActivity " +
                "-a android.intent.action.VIEW -t vnd.android.document/directory " +
                "-d 'content://com.android.externalstorage.documents/document/primary%3ADownload%2FOwly'",
            20,
        )
        Log.i(TAG, "showing the folder as root -> ${out.trim().lines().firstOrNull().orEmpty()}")
        return out.contains("Starting:")
    }

    fun fileSize(path: String): Long =
        su("stat -c %s '${path.replace("'", "'\\''")}'", 10).trim().toLongOrNull() ?: -1L

    fun catTo(path: String, out: java.io.OutputStream): Boolean = try {
        val p = ProcessBuilder("su", "-c", "cat '${path.replace("'", "'\\''")}'").redirectErrorStream(false).start()
        p.outputStream.close()
        p.inputStream.use { it.copyTo(out) }
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); false } else p.exitValue() == 0
    } catch (t: Throwable) {
        Log.w(TAG, "cat $path failed: $t"); false
    }

    private fun su(cmd: String, timeoutSec: Long): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val sb = StringBuffer()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } } }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            sb.append("\n[timed out after ${timeoutSec}s]")
        }
        reader.join(1000)
        sb.toString()
    } catch (t: Throwable) {
        "[su failed: $t]"
    }

    /**
     * Tells the radio watchdog whether AirDrop must stay on 5 GHz even when idle. Only the "turn Wi-Fi off"
     * policy wants that; the others wait on 2.4 GHz so Wi-Fi is not slowed. policy-yield is the marker that
     * used to keep "Wi-Fi yields" on 5 GHz all the time; it is removed so an older watchdog cannot act on it.
     */
    fun syncChannelMarker(stay5Ghz: Boolean) {
        val marker = "/data/adb/owly/policy-5ghz"
        suDetached(
            "[ -d /data/adb/owly ] || exit 0; rm -f /data/adb/owly/policy-yield; " +
                if (stay5Ghz) "[ -f $marker ] || touch $marker" else "rm -f $marker",
        )
    }

    private fun suDetached(cmd: String) {
        try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()
            p.outputStream.close()
            if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Throwable) { }
    }
}
