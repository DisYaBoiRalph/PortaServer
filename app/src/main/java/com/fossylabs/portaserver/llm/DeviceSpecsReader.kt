package com.fossylabs.portaserver.llm

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

class DeviceSpecsReader(private val context: Context) {

    fun read(): DeviceSpecs {
        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        } else null

        return DeviceSpecs(
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            socModel = socModel,
            hasVulkan = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
        )
    }
}
