package de.simon.dankelmann.bluetoothlespam.Services

import android.os.Build
import android.util.Log
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertiseMode
import de.simon.dankelmann.bluetoothlespam.Enums.TxPowerLevel
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet
import de.simon.dankelmann.bluetoothlespam.Models.ManufacturerSpecificData
import de.simon.dankelmann.bluetoothlespam.Models.ServiceData
import android.os.ParcelUuid
import java.util.UUID

/**
 * Optimizes BLE advertisement for different target devices and operating systems.
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
     * Optimizes for iOS devices.
     * iOS requires Apple manufacturer ID (0x004C) for many notifications to be visible.
     */
    fun optimizeForIOS(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for iOS - Using Apple manufacturer ID")
        
        // iOS specific parameters
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_HIGH
            connectable = false
            legacyMode = false
        }
        
        // iOS requires Apple Manufacturer ID (0x004C)
        val hasAppleData = advertisementSet.advertiseData.manufacturerData.any { it.manufacturerId == 0x004C }
        if (!hasAppleData) {
            val appleData = ManufacturerSpecificData().apply {
                manufacturerId = 0x004C
                // Basic Apple payload flags if none exists
                manufacturerSpecificData = byteArrayOf(0x02, 0x15) // Example iBeacon prefix or similar
            }
            advertisementSet.advertiseData.manufacturerData.add(appleData)
        }
        
        return advertisementSet
    }
    
    /**
     * Optimizes for Windows devices.
     * Windows detects better with Service UUIDs and prefers connectable advertisements.
     */
    fun optimizeForWindows(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for Windows - Adding service UUIDs")
        
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_MEDIUM
            connectable = true
            legacyMode = false
        }
        
        // Windows detection often relies on specific Service UUIDs
        val windowsUuid = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
        val hasWindowsService = advertisementSet.advertiseData.services.any { it.serviceUuid?.uuid == windowsUuid }
        if (!hasWindowsService) {
            val serviceData = ServiceData().apply {
                serviceUuid = ParcelUuid(windowsUuid)
            }
            advertisementSet.advertiseData.services.add(serviceData)
        }
        
        return advertisementSet
    }
    
    /**
     * Optimizes for Samsung devices.
     */
    fun optimizeForSamsung(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for Samsung - Using standard Android parameters")
        
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_HIGH
            connectable = false
            legacyMode = true
        }
        
        return advertisementSet
    }
    
    /**
     * Generic Android optimization.
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
     * Optimizes for MAXIMUM SPEED.
     */
    fun optimizeForSpeed(advertisementSet: AdvertisementSet): AdvertisementSet {
        Log.d(logTag, "Optimizing for speed - Removed scan response")
        advertisementSet.scanResponse = null
        advertisementSet.advertisingSetParameters.apply {
            txPowerLevel = TxPowerLevel.TX_POWER_HIGH
            connectable = false
            legacyMode = true
        }
        return advertisementSet
    }
    
    /**
     * Automatically detects the target device and applies optimal settings.
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
     * Detects the current device type.
     */
    private fun detectCurrentDevice(): TargetDevice {
        return when {
            Build.MANUFACTURER.contains("Samsung", ignoreCase = true) -> TargetDevice.SAMSUNG
            // Note: We can't really detect target OS from local Build props, 
            // but we can default to Generic or Speed.
            else -> TargetDevice.GENERIC_ANDROID
        }
    }
}
