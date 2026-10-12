package com.modulex.translator.platform

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.modulex.translator.core.resources.MemorySnapshot
import com.modulex.translator.core.resources.ResourcePolicy
import com.modulex.translator.core.resources.ThermalLevel
import com.modulex.translator.core.resources.WorkBudget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Live device conditions that decide how much work the engines may do. */
class DeviceStatus(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _thermal = MutableStateFlow(ThermalLevel.fromAndroid(power.currentThermalStatus))
    val thermal: StateFlow<ThermalLevel> = _thermal.asStateFlow()

    private val _batterySaver = MutableStateFlow(power.isPowerSaveMode)
    val batterySaver: StateFlow<Boolean> = _batterySaver.asStateFlow()

    private val _internet = MutableStateFlow(false)
    /** Validated internet connectivity (informational; offline features never depend on it). */
    val internet: StateFlow<Boolean> = _internet.asStateFlow()

    init {
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { _thermal.value = ThermalLevel.fromAndroid(it) }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { _batterySaver.value = power.isPowerSaveMode }
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _internet.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun onLost(network: Network) { _internet.value = false }
        })
    }

    fun memory(): MemorySnapshot {
        val mi = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(mi)
        return MemorySnapshot(mi.availMem, mi.totalMem, mi.lowMemory)
    }

    val cpuCores: Int get() = Runtime.getRuntime().availableProcessors()

    fun budget(): WorkBudget = ResourcePolicy.budget(_thermal.value, cpuCores, _batterySaver.value)

    fun freeStorageBytes(): Long = context.filesDir.usableSpace

    /** Process proportional set size, for diagnostics only. */
    fun processPssKb(): Long = runCatching {
        activity.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0].totalPss.toLong()
    }.getOrDefault(-1)

    fun describe(): String = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, " +
        "${Build.SUPPORTED_ABIS.firstOrNull()}, SoC ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else "n/a"})"
}

/**
 * The native engines are compiled for ARMv8.2-A with dot-product and FP16 instructions.
 * Loading them on an older CPU would crash with SIGILL, so the app checks first.
 */
object CpuSupport {
    val supported: Boolean by lazy {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return@lazy false
        val features = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
            .filter { it.startsWith("Features") }
            .flatMap { it.substringAfter(":").trim().split(" ") }
            .toSet()
        "asimddp" in features && ("fphp" in features || "asimdhp" in features)
    }
}
