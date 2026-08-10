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

        // totalMem excludes memory reserved by the kernel and firmware, so it reads well
        // below the RAM the device is sold with (an 8 GB phone reports ~7.5 GiB).
        // advertisedMem reports the nominal figure, which is what tier advice is written
        // against; ModelRecommender's thresholds assume this value.
        val totalRamBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            memInfo.advertisedMem
        } else {
            memInfo.totalMem
        }

        return DeviceSpecs(
            totalRamBytes = totalRamBytes,
            availableRamBytes = memInfo.availMem,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            socModel = socModel,
            hasVulkan = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
        )
    }
}
