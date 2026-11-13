package com.airdrop.android.daemon

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object RadioInstaller {
    private const val TAG = "AirDrop"

    private const val DIR = "/data/adb/o13"
    private const val BOOT = "/data/adb/service.d/airdrop-radio.sh"
    private const val START_LOG = "/data/local/tmp/airdrop-start.log"

    private const val LEGACY_MODULE = "/data/adb/modules/owly"

    private const val STALE_BOOT = "/data/adb/service.d/owly-daemon.sh"
    private const val STALE_WATCH_PID = "/data/adb/owly/stack/watch.pid"

    private const val PREFS = "airdrop"
    private const val KEY_OURS = "radio_installed_by_app"
    private const val KEY_SHARE_ORIG = "share_state_original"
    private const val ISLAND_KEY = "airdrop_island_stamp"

    internal fun assetForModel(model: String): String? = when (model.uppercase(java.util.Locale.ROOT)) {
        "OPD2508", "PKT110", "PLB110" -> "radio-mt6991.zip"
        else -> null
    }

    fun isMt6991(model: String = android.os.Build.MODEL): Boolean =
        model.uppercase(java.util.Locale.ROOT) in setOf("OPD2508", "PKT110", "PLB110")

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

    private fun restoreShareState(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SHARE_ORIG, 0).coerceAtLeast(0)

    @Synchronized
    fun uninstall(ctx: Context): String? {
        if (!hasRoot()) return "no root"
        val script = File(ctx.cacheDir, "airdrop-uninstall.sh")
        return try {
            script.writeText(
                listOf(
                    "D=$DIR",
                    "[ -f /data/adb/owly/wifi-was-on ] && [ \"\$(settings get global wifi_on)\" != 1 ] && svc wifi enable",
                    "[ -f \$D/bin/stack.sh ] && sh \$D/bin/stack.sh down >/dev/null 2>&1",
                    "pkill -f owly-watch.sh; pkill -f speed-watch.sh; pkill -f osharekeep.sh; pkill -x owly",
                    "sleep 1",
                    "for m in owly_coex wonder mtk_wonder_dev; do grep -q \"^\$m \" /proc/modules && rmmod \$m 2>/dev/null; done",
                    "settings put system oshare_state ${restoreShareState(ctx)}",
                    "settings delete global airdrop_awdl_tx; settings delete global $ISLAND_KEY",
                    "rm -f /data/adb/service.d/airdrop-radio.sh /data/adb/service.d/airdrop-speed.sh",
                    "rm -rf \$D \$D.old.* \$D.stage.* \$D-coex-stage /data/adb/owly /data/etc/radio.toml /data/etc/drivers.toml $START_LOG",
                    "rm -f /data/user/0/${Ipc.SHARE_PKG}/files/${Ipc.TOKEN_FILE}",
                    "[ -e \$D ] || [ -e /data/adb/owly ] || echo SKY_REMOVED",
                ).joinToString("\n", postfix = "\n"),
            )
            val out = su("nsenter --mount=/proc/1/ns/mnt -- sh ${script.path} 2>&1 || sh ${script.path} 2>&1", 60)
            if (out.contains("SKY_REMOVED")) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OURS, false).remove(KEY_SHARE_ORIG).apply()
                ctx.getSharedPreferences("airdrop", Context.MODE_PRIVATE).edit().putBoolean("radio_installed", false).putBoolean("airdrop_on", false).apply()
                null
            } else out.trim().lines().lastOrNull().orEmpty().ifBlank { "unknown" }
        } finally {
            script.delete()
        }
    }

    @Synchronized
    fun install(ctx: Context): Failure? {
        val asset = assetForModel(android.os.Build.MODEL) ?: return Failure.UnsupportedDevice
        if (!su("id", 20).contains("uid=0")) return Failure.NoRoot
        if (su("[ -d $LEGACY_MODULE ] && echo PRESENT", 20).contains("PRESENT")) return Failure.ExistingModule
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { sp ->
            if (!sp.contains(KEY_SHARE_ORIG)) {
                val now = runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, "oshare_state", -1) }.getOrDefault(-1)
                sp.edit().putInt(KEY_SHARE_ORIG, if (now == 1) 2 else now).apply()
            }
        }
        val zip = File(ctx.cacheDir, "radio.zip")
        ctx.assets.open(asset).use { i -> zip.outputStream().use { o -> i.copyTo(o) } }
        val boot = File(ctx.cacheDir, "airdrop-radio.sh").apply {
            writeText(
                "#!/system/bin/sh\n" +
                    "ps -A | grep -q ' owly\$' && exit 0\n" +
                    "[ -f $DIR/service.sh ] && cd $DIR && exec sh $DIR/service.sh\n"
            )
        }
        val stage = "$DIR.stage.${System.nanoTime()}"
        val country = java.util.Locale.getDefault().country.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "00"
        val out = su(
            "mkdir $stage || exit 1; " +
                "trap 'rm -rf $stage' EXIT; " +
                "unzip -oq '${zip.path}' -d $stage && " +
                "[ -f $stage/module.prop ] && chmod 755 $stage/owly $stage/service.sh $stage/bin/* || exit 1; " +
                "sed -i 's/^country = .*/country = \"$country\"/' $stage/etc/radio.toml; " +
                "printf '%s' ${restoreShareState(ctx)} > $stage/share-original; " +
                "mkdir -p /data/adb/service.d && cp '${boot.path}' $BOOT.new && " +
                "chmod 755 $BOOT.new && mv -f $BOOT.new $BOOT || exit 1; " +
                "watch_pid=\$(cat $STALE_WATCH_PID 2>/dev/null); " +
                "case \"\$watch_pid\" in ''|*[!0-9]*) ;; *) " +
                "if tr '\\000' ' ' < /proc/\$watch_pid/cmdline 2>/dev/null | grep -q 'owly-watch.sh'; then " +
                "kill \"\$watch_pid\" 2>/dev/null; " +
                "n=0; while kill -0 \"\$watch_pid\" 2>/dev/null; do " +
                "[ \"\$n\" -lt 10 ] || { echo 'Watchdog did not stop'; exit 1; }; sleep 1; n=\$((n + 1)); done; fi;; esac; " +
                "pkill -x owly 2>/dev/null; " +
                "n=0; while pidof owly >/dev/null 2>&1; do " +
                "[ \"\$n\" -lt 10 ] || { echo 'Daemon did not stop'; exit 1; }; sleep 1; n=\$((n + 1)); done; " +
                "[ -f $STALE_BOOT ] && mv -f $STALE_BOOT $STALE_BOOT.parked-by-owly-app; " +
                "chmod 644 /data/adb/service.d/*.parked-by-owly-app 2>/dev/null; " +
                "pkill -f [o]sharekeep.sh 2>/dev/null; " +
                "backup=$DIR.old.\$(date +%s).\$\$; " +
                "if [ -d $DIR ]; then mv $DIR \"\$backup\" || exit 1; fi; " +
                "if mv $stage $DIR; then rm -rf $DIR.old.*; echo SKY_OK; else " +
                "[ ! -d \"\$backup\" ] || mv \"\$backup\" $DIR; exit 1; fi",
            120,
        )
        boot.delete()
        zip.delete()
        if (!out.contains("SKY_OK")) {
            start()
            return Failure.Unpack(out.trim().take(300))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OURS, true).apply()
        start(enableAirDrop = true)
        return null
    }

    fun ours(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OURS, false)

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

    fun start(enableAirDrop: Boolean = false) {
        val on = if (enableAirDrop) "[ \"\$(settings get system oshare_state)\" = 1 ] || settings put system oshare_state 1; " else ""
        suDetached(
            on + "ps -A | grep -q ' owly\$' || (cd $DIR && setsid sh $DIR/service.sh </dev/null >$START_LOG 2>&1 &)",
        )
    }

    fun installedVersion(): String? =
        su("grep ^version= $DIR/module.prop 2>/dev/null", 20).trim().removePrefix("version=").ifEmpty { null }

    fun log(): String = su(
        "echo '== su'; id; echo; " +
            "echo '== process'; ps -A | grep -E ' owly\$'; echo; " +
            "echo '== socket'; grep -a owly-android /proc/net/unix | head -3; echo; " +
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
