package de.simon.dankelmann.bluetoothlespam.Services

import android.os.Build
import android.util.Log
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertiseMode
import de.simon.dankelmann.bluetoothlespam.Enums.TxPowerLevel
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet

/**
 * Optimizes BLE advertisement for different target devices and operating systems.
 * - iOS: Requires Apple manufacturer ID (0x004C) with specific payload format
 * - Windows: Needs Service UUIDs and TX power information
 * - Samsung: Works best with standard Android parameters
 * - Speed Mode: Removes unnecessary components for maximum throughput
 */
class DeviceOptimizedAdvertiser {
    
    private val logTag = "DeviceOptimizedAdvertiser"
    
    // Target device enums
    enum class TargetDevice {
        IOS,
        WINDOWS,
        SAMSUNG,
        GENERIC_ANDROID,
        SPEED_PRIORITY
    }
    
    /**
     * Optimizes advertisement payload and parameters for iOS devices.
     * iOS is very strict about BLE payload format and only detects devices
     * with Apple manufacturer ID in manufacturer-specific data.
     */
    fun optimizeForIOS(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for iOS - Apple manufacturer ID required")
        
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_HIGH
            connectable = false
            legacyMode = false
        }
        
        advertisementSet.advertiseSettings.apply {
            advertiseMode = AdvertiseMode.ADVERTISEMODE_LOW_LATENCY
            connectable = false
        }
        
        return advertisementSet
    }
    
    /**
     * Optimizes advertisement payload and parameters for Windows devices.
     * Windows can detect more payload types including Service UUIDs
     * and manufacturer data, making it more flexible than iOS.
     */
    fun optimizeForWindows(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for Windows - Service UUID approach")
        
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_MEDIUM
            connectable = true
            legacyMode = false
        }
        
        advertisementSet.advertiseSettings.apply {
            advertiseMode = AdvertiseMode.ADVERTISEMODE_BALANCED
            connectable = true
        }
        
        return advertisementSet
    }
    
    /**
     * Optimizes advertisement payload and parameters for Samsung devices.
     * Samsung Android is very permissive with BLE advertising and accepts
     * most standard Android BLE formats.
     */
    fun optimizeForSamsung(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for Samsung - Standard Android parameters")
        
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_HIGH
            connectable = false
            legacyMode = true
        }
        
        advertisementSet.advertiseSettings.apply {
            advertiseMode = AdvertiseMode.ADVERTISEMODE_LOW_LATENCY
            connectable = false
        }
        
        return advertisementSet
    }
    
    /**
     * Generic Android optimization for standard Android devices.
     * Balances compatibility and performance.
     */
    fun optimizeForGenericAndroid(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for generic Android")
        
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_MEDIUM
            connectable = false
        }
        
        return advertisementSet
    }
    
    /**
     * Optimizes for lower latency by removing optional components.
     */
    fun optimizeForSpeed(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for MAXIMUM SPEED - Removed scan response")
        
        // Remove scan response to reduce transmission time
        if (advertisementSet.scanResponse != null) {
            Log.d(logTag, "Removing scan response for speed optimization")
            advertisementSet.scanResponse = null
        }
        
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_ULTRA_LOW
            connectable = false
            legacyMode = true
        }
        
        advertisementSet.advertiseSettings.apply {
            advertiseMode = AdvertiseMode.ADVERTISEMODE_LOW_LATENCY
            connectable = false
        }
        
        return advertisementSet
    }
    
    /**
     * Automatically detects the target device and applies optimal settings.
     * Uses device properties (manufacturer, model) to determine OS.
     */
    fun optimizeForTargetDevice(
        advertisementSet: AdvertisementSet,
        targetDevice: TargetDevice = detectCurrentDevice()
    ): AdvertisementSet {
        return when (targetDevice) {
            TargetDevice.IOS -> optimizeForIOS(advertisementSet)
            TargetDevice.WINDOWS -> optimizeForWindows(advertisementSet)
            TargetDevice.SAMSUNG -> optimizeForSamsung(advertisementSet)
            TargetDevice.GENERIC_ANDROID -> optimizeForGenericAndroid(advertisementSet)
            TargetDevice.SPEED_PRIORITY -> optimizeForSpeed(advertisementSet)
        }
    }
    
    /**
     * Detects the current device type based on build properties.
     * This helps in automatic optimization.
     */
    private fun detectCurrentDevice(): TargetDevice {
        return when {
            Build.MANUFACTURER.equals("Apple", ignoreCase = true) -> TargetDevice.IOS
            Build.MANUFACTURER.equals("Samsung", ignoreCase = true) -> TargetDevice.SAMSUNG
            Build.DEVICE.contains("windows", ignoreCase = true) -> TargetDevice.WINDOWS
            else -> TargetDevice.GENERIC_ANDROID
        }
    }
    
    /**
     * Returns a list of all optimizations available for user selection.
     */
    fun getAvailableOptimizations(): List<String> {
        return listOf(
            "iOS (Apple)",
            "Windows",
            "Samsung",
            "Generic Android",
            "Speed Priority"
        )
    }
    
    /**
     * Gets recommended interval based on optimization type.
     */
    fun getRecommendedInterval(targetDevice: TargetDevice): Long {
        return when (targetDevice) {
            TargetDevice.IOS -> 100L          // iOS slower to process
            TargetDevice.WINDOWS -> 75L       // Windows medium speed
            TargetDevice.SAMSUNG -> 50L       // Samsung fast
            TargetDevice.GENERIC_ANDROID -> 60L // Generic: balanced
            TargetDevice.SPEED_PRIORITY -> 20L  // Speed: ultra-fast
        }
    }
}
