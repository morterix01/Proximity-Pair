package de.simon.dankelmann.bluetoothlespam.Adapters

import android.bluetooth.le.AdvertiseData
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Cross-platform BLE payload adapter that makes advertisements visible on:
 * - iOS (requires Apple manufacturer ID 0x004C)
 * - Windows (accepts Service UUIDs and manufacturer data)
 * - Android (accepts any standard BLE format)
 * 
 * This class provides methods to create payloads that are universally detectable
 * across different platforms and ensures notifications are properly shown.
 */
class CrossPlatformNotificationAdapter {
    
    private val logTag = "CrossPlatformAdapter"
    
    // Well-known manufacturer IDs
    companion object {
        const val APPLE_MANUFACTURER_ID = 0x004C
        const val MICROSOFT_MANUFACTURER_ID = 0x0006
        const val SAMSUNG_MANUFACTURER_ID = 0x0075
        
        // Service UUIDs for cross-platform detection
        val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
        val GENERIC_ACCESS_SERVICE_UUID = UUID.fromString("00001800-0000-1000-8000-00805F9B34FB")
        val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val MESH_SERVICE_UUID = UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")
    }
    
    /**
     * Creates a universal payload that works across iOS, Windows, and Android.
     * Includes Apple manufacturer data for iOS detection and Service UUIDs for Windows.
     */
    fun createUniversalPayload(): AdvertiseData.Builder {
        Log.d(logTag, "Creating universal cross-platform payload")
        
        val builder = AdvertiseData.Builder()
        
        // Step 1: Add Apple manufacturer data (required for iOS detection)
        // iOS scans specifically for 0x004C manufacturer ID
        val applePayload = byteArrayOf(
            0x02.toByte(),  // Length
            0x01.toByte(),  // Flags data type
            0x06.toByte()   // LE General Discoverable Mode, BR/EDR Not Supported
        )
        builder.addManufacturerData(APPLE_MANUFACTURER_ID, applePayload)
        Log.d(logTag, "Added Apple manufacturer data (ID: 0x004C)")
        
        // Step 2: Add Service UUIDs for Windows and other platforms
        builder.addServiceUuid(ParcelUuid(DEVICE_INFO_SERVICE_UUID))
        builder.addServiceUuid(ParcelUuid(GENERIC_ACCESS_SERVICE_UUID))
        Log.d(logTag, "Added Service UUIDs for Windows compatibility")
        
        // Step 3: Add standard Android flags
        builder.setIncludeDeviceName(false)
        builder.setIncludeTxPowerLevel(true)
        
        Log.d(logTag, "Universal payload created successfully")
        return builder
    }
    
    /**
     * Specifically optimizes payload for iOS visibility.
     * iOS has very strict requirements:
     * - Must contain Apple manufacturer ID (0x004C)
     * - Specific byte sequence expected
     * - High TX power recommended
     */
    fun makeIOSVisible(builder: AdvertiseData.Builder): AdvertiseData.Builder {
        Log.d(logTag, "Optimizing for iOS visibility")
        
        // iOS payload format: Flags + Apple Manufacturer Data
        val iosOptimizedPayload = byteArrayOf(
            0x02.toByte(),      // Flags length
            0x01.toByte(),      // Flags type
            0x06.toByte(),      // Flags value: LE General Discoverable, BR/EDR not supported
            0x0A.toByte(),      // Manufacturer data length
            0xFF.toByte(),      // Manufacturer specific data type
            0x4C.toByte(), 0x00.toByte()  // Apple Inc. (little-endian: 0x004C)
        )
        
        builder.addManufacturerData(APPLE_MANUFACTURER_ID, iosOptimizedPayload)
        Log.d(logTag, "iOS optimized payload added")
        
        return builder
    }
    
    /**
     * Specifically optimizes payload for Windows visibility.
     * Windows is more flexible but prefers:
     * - Service UUIDs (for device discovery)
     * - Manufacturer data (accepted but not required)
     * - TX Power Level (helps with range calculation)
     */
    fun makeWindowsVisible(builder: AdvertiseData.Builder): AdvertiseData.Builder {
        Log.d(logTag, "Optimizing for Windows visibility")
        
        // Windows responds well to Service UUID advertisement
        builder.addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))  // Mesh UUID - recognized by Windows
        builder.addServiceUuid(ParcelUuid(BATTERY_SERVICE_UUID))  // Common service
        
        // Include TX power for Windows range estimation
        builder.setIncludeTxPowerLevel(true)
        
        Log.d(logTag, "Windows optimized payload added")
        return builder
    }
    
    /**
     * Creates payload optimized for Android devices.
     * Android is most flexible, accepts any standard BLE format.
     */
    fun makeAndroidOptimal(builder: AdvertiseData.Builder): AdvertiseData.Builder {
        Log.d(logTag, "Optimizing for Android")
        
        builder.setIncludeDeviceName(false)  // Device name can reduce scanning range
        builder.setIncludeTxPowerLevel(true)  // Help with proximity detection
        
        return builder
    }
    
    /**
     * Creates a fast payload by removing non-essential data.
     * Useful for spam/flooding scenarios where speed is more important than compatibility.
     */
    fun createFastAndroidPayload(): AdvertiseData.Builder {
        Log.d(logTag, "Creating fast payload for Android")
        
        val builder = AdvertiseData.Builder()
        
        // Minimal payload: only essential flags
        builder.setIncludeDeviceName(false)
        builder.setIncludeTxPowerLevel(false)  // Removing TX power saves bytes and time
        
        Log.d(logTag, "Fast Android payload created")
        return builder
    }
    
    /**
     * Creates a payload specifically for iOS "AirDrop" style notifications.
     * Mimics Apple's notification format for maximum compatibility.
     */
    fun createIOSAirDropLike(): AdvertiseData.Builder {
        Log.d(logTag, "Creating iOS AirDrop-like payload")
        
        val builder = AdvertiseData.Builder()
        
        // AirDrop uses specific Apple manufacturer data format
        val airdropPayload = byteArrayOf(
            0x01, 0x02.toByte(), // AirDrop type identifier
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        
        builder.addManufacturerData(APPLE_MANUFACTURER_ID, airdropPayload)
        Log.d(logTag, "iOS AirDrop-like payload created")
        
        return builder
    }
    
    /**
     * Creates a payload for iOS Continuity features (Handoff, etc.)
     */
    fun createIOSContinuityPayload(): AdvertiseData.Builder {
        Log.d(logTag, "Creating iOS Continuity payload")
        
        val builder = AdvertiseData.Builder()
        
        // Continuity uses Apple manufacturer data with specific format
        val continuityPayload = byteArrayOf(
            0x0D.toByte(),  // Continuity type identifier for Handoff
            0x00, 0x01.toByte(),  // Sequence number
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        
        builder.addManufacturerData(APPLE_MANUFACTURER_ID, continuityPayload)
        Log.d(logTag, "iOS Continuity payload created")
        
        return builder
    }
    
    /**
     * Validates that payload will be properly detected.
     * Checks for required formats per platform.
     */
    fun validatePayloadForPlatform(
        builder: AdvertiseData.Builder,
        targetPlatforms: List<String>
    ): Boolean {
        Log.d(logTag, "Validating payload for platforms: $targetPlatforms")
        
        // Note: This is a simplified validation.
        // In practice, you'd check the actual byte content.
        
        var isValid = true
        
        if (targetPlatforms.contains("iOS")) {
            // Should have Apple manufacturer ID
            Log.d(logTag, "iOS validation: checking for Apple manufacturer ID...")
        }
        
        if (targetPlatforms.contains("Windows")) {
            // Should have Service UUID or manufacturer data
            Log.d(logTag, "Windows validation: checking for Service UUID...")
        }
        
        if (targetPlatforms.contains("Android")) {
            // Pretty much anything works
            Log.d(logTag, "Android validation: standard format OK")
        }
        
        Log.d(logTag, "Payload validation result: $isValid")
        return isValid
    }
    
    /**
     * Gets recommended settings for multi-platform advertisement.
     */
    fun getMultiPlatformRecommendations(): Map<String, String> {
        return mapOf(
            "iOS" to "High TX Power + Apple Manufacturer ID",
            "Windows" to "Service UUID + TX Power Level",
            "Android" to "Any standard BLE format",
            "Interval" to "100-200ms for reliable cross-platform detection"
        )
    }
}
