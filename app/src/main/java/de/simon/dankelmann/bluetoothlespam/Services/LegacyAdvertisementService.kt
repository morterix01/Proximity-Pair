package de.simon.dankelmann.bluetoothlespam.Services

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.simon.dankelmann.bluetoothlespam.AppContext.AppContext
import de.simon.dankelmann.bluetoothlespam.AppContext.AppContext.Companion.bluetoothAdapter
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementError
import de.simon.dankelmann.bluetoothlespam.Enums.TxPowerLevel
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementServiceCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IBleAdvertisementServiceCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IAdvertisementService
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet
import de.simon.dankelmann.bluetoothlespam.PermissionCheck.PermissionCheck

class LegacyAdvertisementService: IAdvertisementService {

    // private
    private val _logTag = "AdvertisementService"
    private var _bluetoothAdapter:BluetoothAdapter? = null
    private var _advertiser: BluetoothLeAdvertiser? = null
    private var _advertisementServiceCallbacks:MutableList<IAdvertisementServiceCallback> = mutableListOf()
    private var _currentAdvertisementSet: AdvertisementSet? = null
    private var _lastRequestedAdvertisementSet: AdvertisementSet? = null
    private var _txPowerLevel:TxPowerLevel? = null
    private val _retryHandler = Handler(Looper.getMainLooper())
    private var _retryRunnable: Runnable? = null
    private var _retryCount = 0
    private val _maxRetries = 3
    private val _retryDelayMs = 350L
    private var _startAttempts = 0
    private var _startSuccessCount = 0
    private var _startFailureCount = 0
    private var _retryScheduledCount = 0

    init {
        _bluetoothAdapter = AppContext.getContext().bluetoothAdapter()
        if(_bluetoothAdapter != null){
            _advertiser = _bluetoothAdapter!!.bluetoothLeAdvertiser
        }
    }

    override fun startAdvertisement(advertisementSet:AdvertisementSet){
        _lastRequestedAdvertisementSet = advertisementSet
        _retryCount = 0
        clearPendingRetry()
        startAdvertisementInternal(advertisementSet)
    }

    private fun startAdvertisementInternal(advertisementSet: AdvertisementSet){
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
                        _advertiser!!.startAdvertising(preparedAdvertisementSet.advertiseSettings.build(), preparedAdvertisementSet.advertiseData.build(), preparedAdvertisementSet.scanResponse!!.build(), preparedAdvertisementSet.advertisingCallback)
                    } else {
                        _advertiser!!.startAdvertising(preparedAdvertisementSet.advertiseSettings.build(), preparedAdvertisementSet.advertiseData.build(), preparedAdvertisementSet.advertisingCallback)
                    }
                    Log.d(_logTag, "Started Legacy Advertisement")
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

    override fun stopAdvertisement(){
        clearPendingRetry()
        logDiagnostics("stopAdvertisement")
        if(_advertiser != null){
            if(_currentAdvertisementSet != null){
                if(PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, AppContext.getActivity())){
                    _advertiser!!.stopAdvertising(_currentAdvertisementSet!!.advertisingCallback)

                    _advertisementServiceCallbacks.map {
                        it.onAdvertisementSetStop(_currentAdvertisementSet)
                    }
                    _currentAdvertisementSet = null
                    _lastRequestedAdvertisementSet = null
                } else {
                    Log.d(_logTag, "Missing permission to stop advertisement")
                }
            } else {
                Log.d(_logTag, "Current Legacy Advertising Set is null")
            }
        } else {
            Log.d(_logTag, "Advertiser is null")
        }
    }

    override fun setTxPowerLevel(txPowerLevel:TxPowerLevel){
        _txPowerLevel = txPowerLevel
        Log.d(_logTag, "Setting TX POWER")
    }

    override fun getTxPowerLevel(): TxPowerLevel{
        if(_txPowerLevel != null){
            return _txPowerLevel!!
        }
        return TxPowerLevel.TX_POWER_HIGH
    }

    fun prepareAdvertisementSet(advertisementSet: AdvertisementSet):AdvertisementSet{
        if(_txPowerLevel != null){
            advertisementSet.advertiseSettings.txPowerLevel = _txPowerLevel!!
            advertisementSet.advertisingSetParameters.txPowerLevel = _txPowerLevel!!
        }
        advertisementSet.advertisingCallback = getAdvertisingCallback()
        return advertisementSet
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
        return true
    }

    private fun getAdvertisingCallback():AdvertiseCallback{
        return object : AdvertiseCallback() {

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                _startFailureCount += 1

                val advertisementError = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> AdvertisementError.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
                    else -> {AdvertisementError.ADVERTISE_FAILED_UNKNOWN}
                }

                _advertisementServiceCallbacks.map {
                    it.onAdvertisementSetFailed(_currentAdvertisementSet, advertisementError)
                }

                scheduleRetry(advertisementError)
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                super.onStartSuccess(settingsInEffect)
                _startSuccessCount += 1
                _retryCount = 0
                clearPendingRetry()
                _advertisementServiceCallbacks.map {
                    it.onAdvertisementSetSucceeded(_currentAdvertisementSet)
                }
            }
        }
    }

    private fun scheduleRetry(advertisementError: AdvertisementError){
        val retriableError = advertisementError == AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED ||
                advertisementError == AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR ||
                advertisementError == AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS

        if(!retriableError || _retryCount >= _maxRetries){
            return
        }

        val advertisementSet = _lastRequestedAdvertisementSet ?: return
        _retryCount += 1
        _retryScheduledCount += 1
        clearPendingRetry()

        _retryRunnable = Runnable {
            if(_currentAdvertisementSet != null){
                runCatching {
                    _advertiser?.stopAdvertising(_currentAdvertisementSet!!.advertisingCallback)
                }
            }
            startAdvertisementInternal(advertisementSet)
        }
        _retryHandler.postDelayed(_retryRunnable!!, _retryDelayMs * _retryCount)
    }

    private fun clearPendingRetry(){
        _retryRunnable?.let { _retryHandler.removeCallbacks(it) }
        _retryRunnable = null
    }

    private fun logDiagnostics(source: String){
        Log.i(
            _logTag,
            "BLE legacy diagnostics [$source] attempts=$_startAttempts success=$_startSuccessCount failure=$_startFailureCount retryScheduled=$_retryScheduledCount"
        )
    }
}