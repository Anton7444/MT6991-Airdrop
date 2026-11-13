package com.airdrop.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object BleWakeAdvertiser {
    private const val TAG = "AirDrop"

    private const val MARK = "ble-wake"

    private const val APPLE_COMPANY_ID = 0x004C

    private const val AIRDROP_TLV = 0x05
    private const val AIRDROP_TLV_LEN = 0x12

    private const val VERSION_3 = 0x40
    private const val VERSION_3_MARKER = 0x03

    private const val MAX_HOLD_MS = 10 * 60_000L

    private const val SWEEP_MS = 5_000L

    private const val MAX_RETRIES = 3
    private const val RETRY_MS = 2_000L

    private const val START_ANSWER_MS = 6_000L

    private const val SLOW_RETRY_MS = 8_000L

    private var app: Context? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null
    private var running = false
    private var startedAt = 0L
    private var retries = 0
    private var grantTried = false

    private val holdsIn = HashMap<String, Long>()

    private val holds = LinkedHashSet<String>()

    fun attach(ctx: Context) {
        app = ctx.applicationContext
    }

    fun payload(): ByteArray {
        val p = ByteArray(20)
        p[0] = AIRDROP_TLV.toByte()
        p[1] = AIRDROP_TLV_LEN.toByte()
        p[2] = VERSION_3.toByte()
        Random.nextBytes(p, 3, 6)
        p[6] = 0; p[7] = 0; p[8] = 0
        p[9] = VERSION_3_MARKER.toByte()
        for (i in 10..19) p[i] = 0
        return p
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }

    fun start(reason: String, holdMs: Long = 0) {
        val ctx = app
        if (ctx == null) {
            Log.w(TAG, "$MARK start($reason) ignored: no context (attach() not called)")
            return
        }
        onThread {
            holds.add(reason)
            if (holdMs > 0) holdsIn[reason] = SystemClock.elapsedRealtime() + holdMs
            if (running) {
                Log.i(TAG, "$MARK hold+ $reason (holds=${holds.size}) already advertising for ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s")
                armSweep()
                return@onThread
            }
            begin(ctx, reason, "first hold")
        }
    }

    fun stop(reason: String) {
        onThread {
            if (!holds.remove(reason)) return@onThread
            holdsIn.remove(reason)
            Log.i(TAG, "$MARK hold- $reason (holds=${holds.size})")
            if (holds.isEmpty()) end("last hold released")
        }
    }

    fun stopAll(reason: String) {
        onThread {
            if (holds.isEmpty() && !running) return@onThread
            Log.i(TAG, "$MARK stop-all $reason (holds=${holds.toList()})")
            holds.clear(); holdsIn.clear()
            end(reason)
        }
    }

    val isAdvertising: Boolean get() = running

    private var starting = false

    private var startIssuedAt = 0L

    private fun begin(ctx: Context, reason: String, why: String) {
        if (callback != null && !running && SystemClock.elapsedRealtime() - startIssuedAt > START_ANSWER_MS) {
            Log.w(TAG, "$MARK previous start was never answered; abandoning it")
            val adv = advertiser; val cb = callback
            callback = null; advertiser = null
            stopAdvertisement(adv, cb)
        }
        if (starting || callback != null) {
            Log.i(TAG, "$MARK begin($reason) skipped: a start is already in flight")
            return
        }
        starting = true
        try {
            beginInner(ctx, reason, why)
        } finally {
            starting = false
        }
    }

    private fun beginInner(ctx: Context, reason: String, why: String) {
        if (!permissions(ctx)) {
            Log.w(TAG, "$MARK cannot advertise ($why for $reason): BLUETOOTH_ADVERTISE/BLUETOOTH_CONNECT not granted")
            holds.clear(); holdsIn.clear()
            scheduleIdle()
            return
        }

        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null) {
            Log.w(TAG, "$MARK cannot advertise ($why for $reason): no BluetoothAdapter on SDK ${Build.VERSION.SDK_INT}")
            fail(adapterOff = true)
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "$MARK cannot advertise ($why for $reason): adapter ${adapter.state} is not on")
            fail(adapterOff = true)
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w(TAG, "$MARK cannot advertise ($why for $reason): BluetoothAdapter.bluetoothLeAdvertiser is null (adapter ${adapter.state})")
            fail(adapterOff = false)
            return
        }

        val bytes = payload()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(APPLE_COMPANY_ID, bytes)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                onThread {
                    running = true; startedAt = SystemClock.elapsedRealtime(); retries = 0
                    Log.i(
                        TAG,
                        "$MARK advertising AirDrop wake ($why for $reason): mode=${settingsInEffect.mode} " +
                            "tx=${settingsInEffect.txPowerLevel} connectable=${settingsInEffect.isConnectable} " +
                            "company=${"0x%04x".format(APPLE_COMPANY_ID)} payload=${hex(bytes)}",
                    )
                    armSweep()
                }
            }

            override fun onStartFailure(errorCode: Int) {
                onThread {
                    when (errorCode) {
                        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> {
                            running = true; startedAt = SystemClock.elapsedRealtime(); retries = 0
                            Log.w(TAG, "$MARK start answered ALREADY_STARTED; advertisement is up, keeping ${holds.size} hold(s)")
                            armSweep()
                        }
                        else -> {
                            running = false
                            if (callback === this) { callback = null; advertiser = null }
                            Log.w(TAG, "$MARK start failed: ${failureName(errorCode)} (for $reason)")
                            fail(adapterOff = false)
                        }
                    }
                }
            }
        }
        callback = cb
        advertiser = adv
        startIssuedAt = SystemClock.elapsedRealtime()
        try {
            adv.startAdvertising(settings, data, cb)
        } catch (t: Throwable) {
            callback = null; advertiser = null
            Log.w(TAG, "$MARK startAdvertising threw for $reason: $t")
            fail(adapterOff = false)
        }
    }

    private fun fail(adapterOff: Boolean) {
        if (holds.isEmpty()) { scheduleIdle(); return }
        if (callback != null && !running) {
            val adv = advertiser; val cb = callback
            callback = null; advertiser = null
            stopAdvertisement(adv, cb)
        }
        val wait = if (retries >= MAX_RETRIES) SLOW_RETRY_MS else RETRY_MS * (retries + 1)
        if (retries < MAX_RETRIES) retries++
        Log.i(TAG, "$MARK retrying in ${wait}ms (attempt $retries/$MAX_RETRIES, adapterOff=$adapterOff)")
        handler?.postDelayed({
            if (holds.isEmpty() || running) return@postDelayed
            val ctx = app ?: return@postDelayed
            begin(ctx, holds.first(), "retry $retries")
        }, wait)
    }

    private fun end(why: String) {
        val held = holds.isEmpty()
        val adv = advertiser
        val cb = callback
        advertiser = null; callback = null
        if (running || adv != null) {
            val secs = if (startedAt > 0) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0
            Log.i(TAG, "$MARK advertising stopped after ${secs}s ($why, holdsEmpty=$held)")
        }
        running = false; startedAt = 0L; retries = 0
        stopAdvertisement(adv, cb)
        scheduleIdle()
    }

    private fun stopAdvertisement(adv: BluetoothLeAdvertiser?, cb: AdvertiseCallback?) {
        if (adv != null && cb != null && app?.let { granted(it, Manifest.permission.BLUETOOTH_ADVERTISE) } == true) {
            try {
                adv.stopAdvertising(cb)
            } catch (e: SecurityException) {
                Log.w(TAG, "$MARK Bluetooth permission was revoked while stopping: $e")
            } catch (t: Throwable) {
                Log.w(TAG, "$MARK stopAdvertising threw: $t")
            }
        }
    }

    private fun failureName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
        else -> "unknown error code $code"
    }

    private fun permissions(ctx: Context): Boolean {
        if (granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) && granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) return true
        if (!grantTried) {
            grantTried = true
            val pkg = ctx.packageName
            val out = su("pm grant $pkg ${Manifest.permission.BLUETOOTH_ADVERTISE}; pm grant $pkg ${Manifest.permission.BLUETOOTH_CONNECT}; echo done", 25)
            Log.i(TAG, "$MARK granted own BLE permissions via su ($pkg): ${out.replace('\n', ' ')}")
        }
        val ok = granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) && granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        if (!ok) Log.w(TAG, "$MARK permissions still not granted: advertise=${granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE)} connect=${granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)}")
        return ok
    }

    private fun su(cmd: String, timeoutSec: Long): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val sb = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } } }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly() else reader.join(500)
        sb.toString().trim()
    } catch (t: Throwable) {
        "[su failed: $t]"
    }

    private fun granted(ctx: Context, permission: String): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun onThread(block: () -> Unit) {
        handler().post(Runnable { runCatching(block).onFailure { Log.w(TAG, "$MARK advertising task failed: $it") } })
    }

    private fun handler(): Handler {
        handler?.let { return it }
        val t = HandlerThread("airdrop-ble-wake").apply { isDaemon = true; start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    private fun armSweep() {
        val h = handler ?: return
        h.removeCallbacks(sweep)
        if (running || holds.isNotEmpty()) h.postDelayed(sweep, SWEEP_MS)
    }

    private val sweep = Runnable {
        val now = SystemClock.elapsedRealtime()
        holds.filter { holdsIn[it]?.let { d -> d > 0 && d <= now } == true }.forEach {
            Log.i(TAG, "$MARK hold $it expired")
            holds.remove(it); holdsIn.remove(it)
        }
        val ctx = app
        if (holds.isEmpty()) {
            end("no holds left")
        } else if (!running && ctx != null) {
            begin(ctx, holds.first(), "sweep")
        } else {
            armSweep()
        }
    }

    private fun scheduleIdle() {
        if (holds.isNotEmpty() || running) { armSweep(); return }
        val h = handler ?: return
        h.removeCallbacks(sweep)
        val t = thread
        handler = null; thread = null
        h.postAtFrontOfQueue { runCatching { t?.quitSafely() } }
    }
}
