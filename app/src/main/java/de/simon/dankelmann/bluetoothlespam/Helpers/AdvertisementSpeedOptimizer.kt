package de.simon.dankelmann.bluetoothlespam.Helpers

import de.simon.dankelmann.bluetoothlespam.Enums.TxPowerLevel

object AdvertisementSpeedOptimizer {
    
    /**
     * Calcola l'intervallo ottimale based su payload size
     */
    fun getOptimalInterval(payloadSizeBytes: Int): Long {
        return when {
            payloadSizeBytes < 20 -> 20L   // Micro payload: 20ms
            payloadSizeBytes < 31 -> 35L   // Standard: 35ms
            payloadSizeBytes < 50 -> 50L   // Large: 50ms
            else -> 75L                     // XL: 75ms
        }
    }
    
    /**
     * Ottimizza TX Power per velocità vs portata
     */
    fun getOptimalTxPower(speedPriority: Boolean): TxPowerLevel {
        return if (speedPriority) {
            TxPowerLevel.TX_POWER_ULTRA_LOW  // Più veloce, meno portata
        } else {
            TxPowerLevel.TX_POWER_HIGH   // Velocità normale, massima portata
        }
    }
    
    /**
     * Disabilita features non essenziali per velocità
     */
    fun shouldIncludeScanResponse(speedPriority: Boolean): Boolean {
        return !speedPriority  // Disabilita scan response se priorità è velocità
    }
}
