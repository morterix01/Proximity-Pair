package de.simon.dankelmann.bluetoothlespam.Helpers

import android.util.Log
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet

/**
 * Optimizes BLE advertisement speed based on payload size and target device.
 * 
 * Provides methods to:
 * - Calculate optimal intervals based on data size
 * - Adjust TX power for speed vs range trade-offs
 * - Enable/disable expensive features for faster transmission
 * - Profile and monitor advertisement performance
 */
object AdvertisementSpeedOptimizer {
    
    private const val logTag = "AdvertisementSpeedOptimizer"
    
    // Speed mode definitions
    enum class SpeedMode {
        ULTRA_FAST,    // < 30ms interval (battery intensive)
        VERY_FAST,     // 30-50ms interval (high speed)
        FAST,          // 50-100ms interval (balanced)
        BALANCED,      // 100-200ms interval (normal)
        POWER_SAVING   // > 200ms interval (battery friendly)
    }
    
    // Performance metrics
    data class AdvertisementMetrics(
        val payloadSizeBytes: Int,
        val hasScannResponse: Boolean,
        val txPowerLevel: Int,
        val estimatedTimeMs: Long
    )
    
    /**
     * Calculates the optimal interval based on payload size.
     * Smaller payloads can be transmitted faster.
     * 
     * Formula: 
     * - Base time = ~15ms minimum for BLE transmission
     * - Additional time based on payload: 1ms per 5 bytes
     * - Scan response adds 15ms if enabled
     * - Multiply by 1.5x for safety margin
     */
    fun getOptimalInterval(payloadSizeBytes: Int, hasScannResponse: Boolean = true): Long {
        val baseTime = 15L
        val payloadTime = (payloadSizeBytes / 5.0).toLong()
        val scanResponseTime = if (hasScannResponse) 15L else 0L
        val safetyMargin = 1.5
        
        val totalTime = (baseTime + payloadTime + scanResponseTime * safetyMargin).toLong()
        
        Log.d(logTag, "Calculated interval: ${totalTime}ms for ${payloadSizeBytes} bytes" +
                      "${if (hasScannResponse) " + scan response" else ""}")
        
        return totalTime
    }
    
    /**
     * Gets optimal interval based on speed mode priority.
     */
    fun getIntervalForSpeedMode(mode: SpeedMode): Long {
        return when (mode) {
            SpeedMode.ULTRA_FAST -> 20L      // Extreme speed
            SpeedMode.VERY_FAST -> 35L       // High speed
            SpeedMode.FAST -> 50L            // Good speed
            SpeedMode.BALANCED -> 100L       // Normal operation
            SpeedMode.POWER_SAVING -> 300L   // Long interval
        }
    }
    
    /**
     * Determines the best speed mode for given conditions.
     */
    fun calculateOptimalSpeedMode(
        batteryPercent: Int = 100,
        isBackgroundMode: Boolean = false,
        isSpamMode: Boolean = false
    ): SpeedMode {
        return when {
            isSpamMode -> SpeedMode.ULTRA_FAST
            isBackgroundMode && batteryPercent < 20 -> SpeedMode.POWER_SAVING
            batteryPercent < 10 -> SpeedMode.POWER_SAVING
            batteryPercent < 30 -> SpeedMode.BALANCED
            else -> SpeedMode.VERY_FAST
        }
    }
    
    /**
     * Calculates optimal TX power for speed vs range trade-off.
     * 
     * Counter-intuitively:
     * - LOWER TX power = FASTER transmission (less power = less modulation complexity)
     * - HIGHER TX power = SLOWER transmission (more power = more complex modulation)
     * 
     * However, practical differences are minimal (< 5%).
     * This is more about optimization for specific scenarios.
     */
    fun getOptimalTxPower(
        prioritySpeed: Boolean = false,
        priorityRange: Boolean = false
    ): Int {
        return when {
            prioritySpeed -> -20  // Minimum TX power = theoretical speed gain
            priorityRange -> 10   // Maximum TX power = maximum range
            else -> 0             // Balanced: medium TX power
        }
    }
    
    /**
     * Determines if scan response should be included based on speed priority.
     * Scan response adds ~15ms to transmission time.
     * For maximum speed, disable it.
     */
    fun shouldIncludeScanResponse(speedMode: SpeedMode): Boolean {
        return when (speedMode) {
            SpeedMode.ULTRA_FAST -> false
            SpeedMode.VERY_FAST -> false
            SpeedMode.FAST -> true
            SpeedMode.BALANCED -> true
            SpeedMode.POWER_SAVING -> true
        }
    }
    
    /**
     * Calculates estimated transmission time for an advertisement.
     * Helps in determining realistic intervals.
     */
    fun estimateTransmissionTimeMs(metrics: AdvertisementMetrics): Long {
        // BLE advertising packet structure:
        // - Preamble: 1 byte
        // - Access address: 4 bytes
        // - PDU header: 2 bytes
        // - Payload: variable
        // - CRC: 3 bytes
        // Total minimum: 31 bytes
        
        val totalBytes = maxOf(31, metrics.payloadSizeBytes + 10)
        
        // BLE 1M PHY: 1 Mbps = 1 byte per microsecond = 8 microseconds per byte
        val transmissionUs = totalBytes * 8L
        val transmissionMs = transmissionUs / 1000L
        
        // Add turnaround time
        val turnaroundMs = 2L
        
        val totalMs = transmissionMs + turnaroundMs
        
        Log.d(logTag, "Transmission time for ${metrics.payloadSizeBytes}B: ${totalMs}ms")
        
        return totalMs
    }
    
    /**
     * Generates optimization recommendations based on payload characteristics.
     */
    fun getOptimizationRecommendations(advertisementSet: AdvertisementSet): List<String> {
        val recommendations = mutableListOf<String>()
        
        // Analyze payload size
        val payloadSize = advertisementSet.advertiseData.build().bytes.size
        
        if (payloadSize > 30) {
            recommendations.add("⚠️ Payload is large (${payloadSize}B). Consider removing non-essential data.")
        }
        
        if (advertisementSet.scanResponse != null) {
            val scanResponseSize = advertisementSet.scanResponse!!.build().bytes.size
            if (scanResponseSize > 10) {
                recommendations.add("💡 Scan response is large (${scanResponseSize}B). Remove for speed.")
            }
            recommendations.add("🚀 Disable scan response to improve advertisement speed by ~15ms.")
        }
        
        // Check TX power
        val txPower = advertisementSet.advertisingSetParameters
        recommendations.add("💪 Current TX power is at ${advertisementSet.advertiseSettings.txPowerLevel}")
        
        // Speed recommendations
        val interval = getOptimalInterval(payloadSize, advertisementSet.scanResponse != null)
        recommendations.add("⏱️ Recommended minimum interval: ${interval}ms")
        
        if (interval < 50) {
            recommendations.add("🔥 Your payload allows ULTRA-FAST transmission mode!")
        }
        
        return recommendations
    }
    
    /**
     * Profiles advertisement performance and provides metrics.
     */
    fun profileAdvertisement(
        payloadSizeBytes: Int,
        hasScannResponse: Boolean,
        txPowerLevel: Int
    ): AdvertisementMetrics {
        val estimatedTime = estimateTransmissionTimeMs(
            AdvertisementMetrics(payloadSizeBytes, hasScannResponse, txPowerLevel, 0)
        )
        
        return AdvertisementMetrics(
            payloadSizeBytes = payloadSizeBytes,
            hasScannResponse = hasScannResponse,
            txPowerLevel = txPowerLevel,
            estimatedTimeMs = estimatedTime
        )
    }
    
    /**
     * Calculates throughput in advertisements per second.
     */
    fun calculateThroughput(intervalMs: Long): Double {
        return if (intervalMs > 0) 1000.0 / intervalMs else 0.0
    }
    
    /**
     * Provides speed comparison between different configurations.
     */
    fun compareConfigurations(configs: List<Pair<String, Long>>): String {
        if (configs.isEmpty()) return "No configurations to compare"
        
        val fastestInterval = configs.minOf { it.second }
        val fastestName = configs.first { it.second == fastestInterval }.first
        
        val report = StringBuilder()
        report.append("Speed Comparison Report:\n")
        report.append("=======================\n\n")
        
        configs.forEach { (name, interval) ->
            val throughput = calculateThroughput(interval)
            val speedFactor = (fastestInterval.toDouble() / interval)
            
            report.append("$name:\n")
            report.append("  Interval: ${interval}ms\n")
            report.append("  Throughput: ${"%.1f".format(throughput)} ads/sec\n")
            report.append("  Speed vs fastest: ${"%.1f".format(speedFactor)}x\n\n")
        }
        
        report.append("Fastest: $fastestName (${fastestInterval}ms)")
        
        return report.toString()
    }
    
    /**
     * Estimates battery impact of continuous advertising.
     */
    fun estimateBatteryImpact(
        intervalMs: Long,
        continuousMinutes: Int,
        txPowerLevel: Int = 0  // Medium
    ): String {
        // Rough estimates based on BLE specs
        val baseCurrentMa = 5.0  // mA at medium power
        val powerAdjustment = when (txPowerLevel) {
            -20 -> 0.5   // Ultra-low
            -10 -> 1.0   // Low
            0 -> 1.0     // Medium
            10 -> 2.0    // High
            else -> 1.0
        }
        
        val actualCurrentMa = baseCurrentMa * powerAdjustment
        
        // Duty cycle: advertising time / total time
        val advertisingTimeMs = 10L  // Approximate advertising packet time
        val dutyCycle = advertisingTimeMs.toDouble() / intervalMs.toDouble()
        
        val effectiveCurrentMa = actualCurrentMa * dutyCycle
        val energyMah = (effectiveCurrentMa * continuousMinutes) / 60.0
        
        return """
            Battery Impact Estimate:
            - TX Power Level: $txPowerLevel dBm
            - Interval: ${intervalMs}ms
            - Duration: $continuousMinutes minutes
            - Estimated drain: ${"%.1f".format(energyMah)} mAh
            - On typical 3000mAh battery: ${"%.1f".format((energyMah/3000)*100)}%
        """.trimIndent()
    }
}
