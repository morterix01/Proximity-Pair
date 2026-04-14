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

class ModernAdvertisementService: IAdvertisementService{

    // private
    private val _logTag = "AdvertisementService"
    private var _bluetoothAdapter: BluetoothAdapter? = null
    private var _advertiser: BluetoothLeAdvertiser? = null
    private var _advertisementServiceCallbacks:MutableList<IAdvertisementServiceCallback> = mutableListOf()
    private var _currentAdvertisementSet: AdvertisementSet? = null
    private var _lastRequestedAdvertisementSet: AdvertisementSet? = null
    private var _txPowerLevel:TxPowerLevel? = null
    private val _retryHandler = Handler(Looper.getMainLooper())
    private var _retryRunnable: Runnable? = null
    private var _retryCount = 0
    private var _maxRetries = 2
    private var _retryDelayMs = 100L

    // Payload blocked quick fallback
    private var _payloadBlockedRetryCount = 0
    private val _maxPayloadBlockedRetries = 1
    private val _payloadBlockedRetryDelayMs = 50L
    private var _startAttempts = 0
    private var _startSuccessCount = 0
    private var _startFailureCount = 0
    private var _retryScheduledCount = 0
    private val _deviceOptimizer = DeviceOptimizedAdvertiser()

    init {
        _bluetoothAdapter = AppContext.getContext().bluetoothAdapter()
        if(_bluetoothAdapter != null){
            _advertiser = _bluetoothAdapter!!.bluetoothLeAdvertiser
        }
    }

    fun prepareAdvertisementSet(advertisementSet: AdvertisementSet):AdvertisementSet{
        if(_txPowerLevel != null){
            advertisementSet.advertiseSettings.txPowerLevel = _txPowerLevel!!
            advertisementSet.advertisingSetParameters.txPowerLevel = _txPowerLevel!!
        }
        advertisementSet.advertisingSetCallback = getAdvertisingSetCallback()
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
        if(_bluetoothAdapter?.isEnabled != true){
            Log.d(_logTag, "Bluetooth disabled, cannot start advertisement")
            return
        }

        if(_advertiser == null && _bluetoothAdapter != null){
            _advertiser = _bluetoothAdapter!!.bluetoothLeAdvertiser
        }

        if(_advertiser != null){
            if(advertisementSet.validate()){
                if(PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, AppContext.getActivity())){
                    val preparedAdvertisementSet = prepareAdvertisementSet(advertisementSet)
                    if(preparedAdvertisementSet.scanResponse != null){
                        _advertiser!!.startAdvertisingSet(preparedAdvertisementSet.advertisingSetParameters.build(), preparedAdvertisementSet.advertiseData.build(), preparedAdvertisementSet.scanResponse!!.build(), null, null, preparedAdvertisementSet.advertisingSetCallback)

                    } else {
                        _advertiser!!.startAdvertisingSet(preparedAdvertisementSet.advertisingSetParameters.build(), preparedAdvertisementSet.advertiseData.build(), null, null, null, preparedAdvertisementSet.advertisingSetCallback)
                    }
                    Log.d(_logTag, "Started Modern Advertisement")
                    _currentAdvertisementSet = preparedAdvertisementSet
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
        logDiagnostics("stopAdvertisement")
        if(_advertiser != null){
            if(_currentAdvertisementSet != null){
                if(PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, AppContext.getActivity())){
                    _advertiser!!.stopAdvertisingSet(_currentAdvertisementSet!!.advertisingSetCallback)
                    _currentAdvertisementSet = null
                    _lastRequestedAdvertisementSet = null
                } else {
                    Log.d(_logTag, "Missing permission to stop advertisement")
                }
            } else {
                Log.d(_logTag, "Current Modern Advertising Set is null")
            }
        } else {
            Log.d(_logTag, "Advertiser is null")
        }
    }

    override fun setTxPowerLevel(txPowerLevel: TxPowerLevel) {
        _txPowerLevel = txPowerLevel
    }

    override fun getTxPowerLevel(): TxPowerLevel{
        if(_txPowerLevel != null){
            return _txPowerLevel!!
        }
        return TxPowerLevel.TX_POWER_HIGH
    }

    override fun addAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(!_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.add(callback)
        }
    }
    override fun removeAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.remove(callback)
        }
    }

    override fun isLegacyService(): Boolean {
        return false
    }

    private fun getAdvertisingSetCallback(): AdvertisingSetCallback {
        return object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if(status == AdvertisingSetCallback.ADVERTISE_SUCCESS){
                    // SUCCESS
                    _startSuccessCount += 1
                    _retryCount = 0
                    clearPendingRetry()
                    _advertisementServiceCallbacks.map{
                        it.onAdvertisementSetSucceeded(_currentAdvertisementSet)
                    }
                    _payloadBlockedRetryCount = 0
                } else{
                    _startFailureCount += 1
                    // FAIL
                    val advertisementError = when (status) {
                        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED
                        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> AdvertisementError.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR
                        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
                        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
                        else -> {AdvertisementError.ADVERTISE_FAILED_UNKNOWN}
                    }

                    _advertisementServiceCallbacks.map{
                        it.onAdvertisementSetFailed(_currentAdvertisementSet, advertisementError)
                    }

                    scheduleRetry(advertisementError)
                }
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet, status: Int) {

            }

            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet, status: Int) {

            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet) {
                _advertisementServiceCallbacks.map{
                    it.onAdvertisementSetStop(_currentAdvertisementSet)
                }
            }
        }
    }

    private fun scheduleRetry(advertisementError: AdvertisementError){
        val isPayloadBlocked = advertisementError == AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
        val retriableError = isPayloadBlocked ||
                advertisementError == AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED ||
                advertisementError == AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR ||
                advertisementError == AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS

        if(!retriableError){
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
            if(_currentAdvertisementSet != null){
                runCatching {
                    _advertiser?.stopAdvertisingSet(_currentAdvertisementSet!!.advertisingSetCallback)
                }
            }
            startAdvertisementInternal(advertisementSet)
        }

        val delay = if (isPayloadBlocked) _payloadBlockedRetryDelayMs else (_retryDelayMs * _retryCount)
        _retryHandler.postDelayed(_retryRunnable!!, delay)
    }

    private fun clearPendingRetry(){
        _retryRunnable?.let { _retryHandler.removeCallbacks(it) }
        _retryRunnable = null
    }

    private fun logDiagnostics(source: String){
        Log.i(
            _logTag,
            "BLE modern diagnostics [$source] attempts=$_startAttempts success=$_startSuccessCount failure=$_startFailureCount retryScheduled=$_retryScheduledCount"
        )
    }

}