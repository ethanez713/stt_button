package com.ezhou.sttbutton

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

class AndroidCostProbe(context: Context) : CostProbe {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    private fun memory() = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)

    override fun elapsedMs() = SystemClock.elapsedRealtime()
    override fun currentNowUa() = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    override fun chargeCounterUah() = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
    override fun batteryPct() = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    override fun charging() = battery.isCharging
    override fun pssKb() = Debug.getPss().toInt()
    override fun availMemKb() = memory().availMem / 1024
    override fun lowMemory() = memory().lowMemory
    override fun thermalStatus() = power.currentThermalStatus
    override fun cpuMs() = Process.getElapsedCpuTime()
}
