package de.simon.dankelmann.bluetoothlespam.Adapters

import android.bluetooth.le.AdvertiseData
import android.os.ParcelUuid
import de.simon.dankelmann.bluetoothlespam.Models.ManufacturerSpecificData
import java.util.*

class CrossPlatformNotificationAdapter {
    
    /**
     * Crea payload compatibile iOS + Windows + Android
     * iOS: Richiede Manufacturer Data con ID Apple (0x004C)
     * Windows: Accetta Service UUID o Manufacturer Data
     * Android: Accetta tutto
     */
    fun createUniversalPayload(): AdvertiseData.Builder {
        val builder = AdvertiseData.Builder()
        
        // Android SDK AdvertiseData.Builder
        
        // ✅ iOS: Manufacturer data con Apple ID
        val appleData = byteArrayOf(
            0x02, 0x01, 0x06,  // Flags
            0x00, 0x4C.toByte() // Apple manufacturer ID
        )
        builder.addManufacturerData(0x004C, appleData)
        
        // ✅ Windows: Service UUID (Windows Detection)
        // UUID per Windows devices
        builder.addServiceUuid(ParcelUuid(UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")))
        
        // ✅ Android: Generic flags
        builder.setIncludeDeviceName(false)
        builder.setIncludeTxPowerLevel(true)
        
        return builder
    }
    
    /**
     * Ottimizza il payload per essere visibile su iOS
     */
    fun makeIOSVisible(builder: AdvertiseData.Builder): AdvertiseData.Builder {
        // iOS cerca dispositivi con manufacturer data specifico
        val iosPayload = byteArrayOf(
            0x02, 0x01, 0x06,    // Flags: LE General Discoverable Mode
            0x0A, 0xFF.toByte(), // AD Type: Manufacturer Specific Data (0xFF)
            0x4C.toByte(), 0x00  // Apple Inc. (0x004C)
        )
        builder.addManufacturerData(0x004C, iosPayload)
        return builder
    }
    
    /**
     * Ottimizza il payload per essere visibile su Windows
     */
    fun makeWindowsVisible(builder: AdvertiseData.Builder): AdvertiseData.Builder {
        // Windows rileva meglio con Service UUIDs
        builder.addServiceUuid(ParcelUuid(UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")))
        builder.setIncludeTxPowerLevel(true)
        return builder
    }
    
    /**
     * Payload veloce per Android (nessun scan response)
     */
    fun createFastAndroidPayload(): AdvertiseData.Builder {
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
    }
}
