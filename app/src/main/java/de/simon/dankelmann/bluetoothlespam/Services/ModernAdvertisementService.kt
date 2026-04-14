package de.simon.dankelmann.bluetoothlespam.Services

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import de.simon.dankelmann.bluetoothlespam.AppContext.AppContext
import de.simon.dankelmann.bluetoothlespam.AppContext.AppContext.Companion.bluetoothAdapter
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementError
import de.simon.dankelmann.bluetoothlespam.Enums.TxPowerLevel
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementServiceCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IAdvertisementService
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet
import de.simon.dankelmann.bluetoothlespam.PermissionCheck.PermissionCheck
import java.util.concurrent.ConcurrentHashMap

class ModernAdvertisementService: IAdvertisementService{

    // private
    private val _logTag = "AdvertisementService"
    private var _bluetoothAdapter: BluetoothAdapter? = null
    private var _advertiser: BluetoothLeAdvertiser? = null
    private var _advertisementServiceCallbacks: MutableList<IAdvertisementServiceCallback> = mutableListOf()
    private var _lastRequestedAdvertisementSet: AdvertisementSet? = null
    private var _txPowerLevel: TxPowerLevel? = null
    private val _retryHandler = Handler(Looper.getMainLooper())
    private var _retryRunnable: Runnable? = null
    private var _retryCount = 0
    private var _maxRetries = 2
    private var _retryDelayMs = 10L // Molto più veloce

    // BURST MODE: Mappe per supportare sessioni multiple thread-safe!
    private val _activeAdvertisers = ConcurrentHashMap<AdvertisingSetCallback, AdvertisementSet>()
    private val _maxConcurrentSlots = 1 // Limitato a 1 per evitare blocco hardware su Android che non supportano multi-slot

    // Payload blocked quick fallback
    private var _payloadBlockedRetryCount = 0
    private val _maxPayloadBlockedRetries = 1
    private val _payloadBlockedRetryDelayMs = 20L
    private var _startAttempts = 0
    private var _startSuccessCount = 0
    private var _startFailureCount = 0
    private var _retryScheduledCount = 0
    private val _deviceOptimizer = DeviceOptimizedAdvertiser()

    init {
        _bluetoothAdapter = AppContext.getContext().bluetoothAdapter()
        if (_bluetoothAdapter != null) {
            _advertiser = _bluetoothAdapter!!.bluetoothLeAdvertiser
        }
    }

    fun prepareAdvertisementSet(advertisementSet: AdvertisementSet): AdvertisementSet {
        if (_txPowerLevel != null) {
            advertisementSet.advertiseSettings.txPowerLevel = _txPowerLevel!!
            advertisementSet.advertisingSetParameters.txPowerLevel = _txPowerLevel!!
        }
        // Non sovrascriviamo più il callback nell'oggetto condiviso!
        // Genereremo il callback fresco al momento dello start.
        return advertisementSet
    }


    // Callback Implementation
    override fun startAdvertisement(advertisementSet: AdvertisementSet) {
        _lastRequestedAdvertisementSet = advertisementSet
        _retryCount = 0
        clearPendingRetry()
        startAdvertisementInternal(advertisementSet)
    }

    private fun startAdvertisementInternal(advertisementSet: AdvertisementSet) {
        _startAttempts += 1
        if (_bluetoothAdapter?.isEnabled != true) {
            Log.d(_logTag, "Bluetooth disabled, cannot start advertisement")
            return
        }

        if (_advertiser == null && _bluetoothAdapter != null) {
            _advertiser = _bluetoothAdapter!!.bluetoothLeAdvertiser
        }

        if (_advertiser != null) {
            if (advertisementSet.validate()) {
                if (PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, AppContext.getActivity())) {
                    val preparedAdvertisementSet = prepareAdvertisementSet(advertisementSet)

                    // BURST MODE: Check Concurrent Limit
                    if (_activeAdvertisers.size >= _maxConcurrentSlots) {
                        // Rimuoviamo il più vecchio FISICAMENTE
                        val oldestCallback = _activeAdvertisers.keys.first()
                        try {
                            _advertiser!!.stopAdvertisingSet(oldestCallback)
                        } catch (e: Exception) {
                            // Ignore
                        }
                        _activeAdvertisers.remove(oldestCallback)
                    }

                    // Crea un nuovo callback UNIVOCO per questa sessione.
                    val advertisingSetCallback = getAdvertisingSetCallback(preparedAdvertisementSet)
                    
                    // Aggiungiamo alla mappa dei burst attivi
                    _activeAdvertisers[advertisingSetCallback] = preparedAdvertisementSet

                    if (preparedAdvertisementSet.scanResponse != null) {
                        _advertiser!!.startAdvertisingSet(
                            preparedAdvertisementSet.advertisingSetParameters.build(),
                            preparedAdvertisementSet.advertiseData.build(),
                            preparedAdvertisementSet.scanResponse!!.build(),
                            null,
                            null,
                            advertisingSetCallback
                        )

                    } else {
                        _advertiser!!.startAdvertisingSet(
                            preparedAdvertisementSet.advertisingSetParameters.build(),
                            preparedAdvertisementSet.advertiseData.build(),
                            null,
                            null,
                            null,
                            advertisingSetCallback
                        )
                    }
                    Log.d(_logTag, "Started Burst Advertisement [${preparedAdvertisementSet.title}], Concurrent: ${_activeAdvertisers.size}")

                    _advertisementServiceCallbacks.map {
                        it.onAdvertisementSetStart(advertisementSet)
                    }
                } else {
                    Log.d(_logTag, "Missing permission to execute advertisement")
                }
            } else {
                Log.d(_logTag, "Advertisement Set could not be validated")
            }
        } else {
            Log.d(_logTag, "Advertiser is null")
        }
    }

    override fun stopAdvertisement() {
        clearPendingRetry()
        logDiagnostics("stopAdvertisement Burst")
        if (_advertiser != null) {
            if (PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, AppContext.getActivity())) {
                _activeAdvertisers.keys.forEach { cb ->
                    try {
                        _advertiser!!.stopAdvertisingSet(cb)
                    } catch (e: Exception) {
                        Log.e(_logTag, "Error stopping burst set: ${e.message}")
                    }
                }
                _activeAdvertisers.clear()
            } else {
                Log.d(_logTag, "Missing permission to stop advertisement")
            }
        } else {
            Log.d(_logTag, "Advertiser is null")
        }
    }

    override fun setTxPowerLevel(txPowerLevel: TxPowerLevel) {
        _txPowerLevel = txPowerLevel
    }

    override fun getTxPowerLevel(): TxPowerLevel {
        if (_txPowerLevel != null) {
            return _txPowerLevel!!
        }
        return TxPowerLevel.TX_POWER_HIGH
    }

    override fun addAdvertisementServiceCallback(callback: IAdvertisementServiceCallback) {
        if (!_advertisementServiceCallbacks.contains(callback)) {
            _advertisementServiceCallbacks.add(callback)
        }
    }

    override fun removeAdvertisementServiceCallback(callback: IAdvertisementServiceCallback) {
        if (_advertisementServiceCallbacks.contains(callback)) {
            _advertisementServiceCallbacks.remove(callback)
        }
    }

    override fun isLegacyService(): Boolean {
        return false
    }

    private fun getAdvertisingSetCallback(advertisementSet: AdvertisementSet): AdvertisingSetCallback {
        return object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    // SUCCESS
                    _startSuccessCount += 1
                    _retryCount = 0
                    clearPendingRetry()
                    _advertisementServiceCallbacks.map {
                        it.onAdvertisementSetSucceeded(advertisementSet)
                    }
                    _payloadBlockedRetryCount = 0
                } else {
                    _startFailureCount += 1
                    _activeAdvertisers.remove(this) // Fallito, toglierlo dalla mappa

                    // Se è TOO_MANY_ADVERTISERS, l'hardware è saturo. Chiudiamo brutalmente la coda.
                    if(status == AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS){
                        stopAdvertisement()
                    }

                    // FAIL
                    val advertisementError = when (status) {
                        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED
                        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> AdvertisementError.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR
                        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
                        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
                        else -> {
                            AdvertisementError.ADVERTISE_FAILED_UNKNOWN
                        }
                    }

                    _advertisementServiceCallbacks.map {
                        it.onAdvertisementSetFailed(advertisementSet, advertisementError)
                    }

                    if(status != AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                        scheduleRetry(advertisementError)
                    }
                }
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet, status: Int) {

            }

            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet, status: Int) {

            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet) {
                _activeAdvertisers.remove(this)
                _advertisementServiceCallbacks.map {
                    it.onAdvertisementSetStop(advertisementSet)
                }
            }
        }
    }

    private fun scheduleRetry(advertisementError: AdvertisementError) {
        val isPayloadBlocked = advertisementError == AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
        val retriableError = isPayloadBlocked ||
                advertisementError == AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED ||
                advertisementError == AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR

        if (!retriableError) {
            return
        }

        if (isPayloadBlocked) {
            if (_payloadBlockedRetryCount >= _maxPayloadBlockedRetries) {
                Log.w(_logTag, "Payload blocked (Data too large) - skipping advertisement")
                return
            }
            _payloadBlockedRetryCount += 1
        } else {
            if (_retryCount >= _maxRetries) {
                return
            }
            _retryCount += 1
        }

        val advertisementSet = _lastRequestedAdvertisementSet ?: return
        _retryScheduledCount += 1
        clearPendingRetry()

        _retryRunnable = Runnable {
            startAdvertisementInternal(advertisementSet)
        }

        val delay = if (isPayloadBlocked) _payloadBlockedRetryDelayMs else (_retryDelayMs * _retryCount)
        _retryHandler.postDelayed(_retryRunnable!!, delay)
    }

    private fun clearPendingRetry() {
        _retryRunnable?.let { _retryHandler.removeCallbacks(it) }
        _retryRunnable = null
    }

    private fun logDiagnostics(source: String) {
        Log.i(
            _logTag,
            "BLE modern diagnostics [$source] attempts=$_startAttempts success=$_startSuccessCount failure=$_startFailureCount retryScheduled=$_retryScheduledCount BurstSlots=${_activeAdvertisers.size}"
        )
    }

}