package de.simon.dankelmann.bluetoothlespam.Services

import android.app.Notification
import android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavDeepLinkBuilder
import de.simon.dankelmann.bluetoothlespam.AppContext.AppContext
import de.simon.dankelmann.bluetoothlespam.Enums.SpamPackageType
import de.simon.dankelmann.bluetoothlespam.Enums.stringRes
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IBluetoothLeScanCallback
import de.simon.dankelmann.bluetoothlespam.MainActivity
import de.simon.dankelmann.bluetoothlespam.Models.FlipperDeviceScanResult
import de.simon.dankelmann.bluetoothlespam.Models.SpamPackageScanResult
import de.simon.dankelmann.bluetoothlespam.R

class BluetoothLeScanForegroundService : IBluetoothLeScanCallback, Service() {

    private val _logTag = "AdvertisementScanForegroundService"
    private val _channelId = "BluetoothLeSpamScanService"
    private val _channelName = "Bluetooth Le Spam Scan Service"
    private val _channelDescription = "Bluetooth Le Spam Notifications"
    private val _binder: IBinder = LocalBinder()
    private var notifyOnNewSpam = true
    private var notifyOnNewFlipper = true
    private var _devicesFoundCount = 0
    private var _scanCallbackRegistered = false

    companion object {
        private val _logTag = "AdvertisementScanForegroundService"
        fun startService(context: Context, message: String) {
            val startIntent = Intent(context, BluetoothLeScanForegroundService::class.java)
            startIntent.putExtra("inputExtra", message)
            ContextCompat.startForegroundService(context, startIntent)
        }
        fun stopService(context: Context) {
            val stopIntent = Intent(context, BluetoothLeScanForegroundService::class.java)
            context.stopService(stopIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            2,
            createNotification(
                getString(R.string.spam_detecting_title),
                getString(R.string.spam_detecting_text),
                false
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(_logTag, "Started BLE Scan Foreground Service")
        val scanService = AppContext.getBluetoothLeScanService()
        if(!_scanCallbackRegistered){
            scanService.addBluetoothLeScanServiceCallback(this)
            _scanCallbackRegistered = true
        }
        if(!scanService.isScanning()){
            scanService.startScanning()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = _binder

    inner class LocalBinder : Binder() {
        val service: BluetoothLeScanForegroundService
            get() = this@BluetoothLeScanForegroundService
    }

    override fun onDestroy() {
        super.onDestroy()
        val scanService = AppContext.getBluetoothLeScanService()
        scanService.stopScanning()
        if(_scanCallbackRegistered){
            scanService.removeBluetoothLeScanServiceCallback(this)
            _scanCallbackRegistered = false
        }
        Log.d(Companion._logTag, "Destroying the Service")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppContext.getActivity() != null) {
            val notificationManager =
                AppContext.getActivity().getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val mChannel = NotificationChannel(_channelId, _channelName, NotificationManager.IMPORTANCE_HIGH)
            mChannel.description = _channelDescription
            mChannel.enableLights(true)
            mChannel.lightColor = Color.BLUE
            notificationManager.createNotificationChannel(mChannel)
        }
    }

    private fun createNotification(title: String, subTitle: String, alertOnlyOnce: Boolean): Notification {

        val pendingIntentTargeted = NavDeepLinkBuilder(this)
            .setComponentName(MainActivity::class.java)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.nav_spam_detector)
            .createPendingIntent()

        val notificationView =
            RemoteViews(packageName, R.layout.bluetooth_le_scan_foreground_service_notification)

        notificationView.setTextViewText(R.id.bluetoothLeScanningForegroundNotificationTitle, title)

        // Show device count if any devices found, otherwise the provided subtitle
        val displaySubtitle = if (_devicesFoundCount > 0) {
            getString(R.string.notification_scan_devices_found, _devicesFoundCount)
        } else {
            subTitle
        }
        notificationView.setTextViewText(R.id.bluetoothLeScanningForegroundNotificationSubTitle, displaySubtitle)

        // Stop button
        val stopIntent = Intent(AppContext.getActivity(), ScanStopButtonListener::class.java)
        val pendingStopIntent = PendingIntent.getBroadcast(
            AppContext.getActivity(), 0, stopIntent, PendingIntent.FLAG_MUTABLE
        )
        notificationView.setOnClickPendingIntent(
            R.id.bluetoothLeScanningForegroundNotificationStopImageView,
            pendingStopIntent
        )

        return NotificationCompat.Builder(AppContext.getActivity(), _channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(displaySubtitle)
            .setSmallIcon(R.drawable.bluetooth)
            .setContentIntent(pendingIntentTargeted)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setChannelId(_channelId)
            .setOngoing(true)
            .setOnlyAlertOnce(alertOnlyOnce)
            .setCustomBigContentView(notificationView)
            .setCustomContentView(notificationView)
            .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    private fun updateNotification(title: String, subTitle: String, alertOnlyOnce: Boolean, id: Int) {
        if (AppContext.getBluetoothLeScanService().isScanning()) {
            val notification = createNotification(title, subTitle, alertOnlyOnce)
            val notificationManager =
                AppContext.getActivity().getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(id, notification)
        }
    }

    private fun refreshDeviceCount() {
        _devicesFoundCount =
            AppContext.getBluetoothLeScanService().getFlipperDevicesList().size +
            AppContext.getBluetoothLeScanService().getSpamPackageScanResultList().size
    }

    // Stop button broadcast receiver
    class ScanStopButtonListener : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopService(context)
        }
    }

    // ── Scan callbacks ────────────────────────────────────────────────────────

    override fun onScanResult(scanResult: ScanResult) {
        // no-op: we update on higher-level events
    }

    override fun onFlipperDeviceDetected(flipperDeviceScanResult: FlipperDeviceScanResult, alreadyKnown: Boolean) {
        refreshDeviceCount()
        if (!alreadyKnown || notifyOnNewFlipper) {
            val title = getString(R.string.spam_detected_flipper_title)
            val subTitle = "${flipperDeviceScanResult.deviceName}  ·  ${flipperDeviceScanResult.rssi} dBm"
            updateNotification(title, subTitle, !notifyOnNewFlipper, 2)
        }
        notifyOnNewFlipper = false
    }

    override fun onFlipperListUpdated() {
        refreshDeviceCount()
        if (AppContext.getBluetoothLeScanService().getFlipperDevicesList().isEmpty()) {
            notifyOnNewFlipper = true
        }
        updateNotification(
            getString(R.string.spam_detecting_title),
            getString(R.string.spam_detecting_text),
            true, 2
        )
    }

    override fun onSpamResultPackageDetected(spamPackageScanResult: SpamPackageScanResult, alreadyKnown: Boolean) {
        refreshDeviceCount()
        val spamPackageTypeText = getString(spamPackageScanResult.spamPackageType.stringRes())
        val title = getString(R.string.spam_detected_title)
        val subTitle = "$spamPackageTypeText  ·  ${spamPackageScanResult.address}"
        updateNotification(title, subTitle, !notifyOnNewSpam, 2)
        notifyOnNewSpam = false
    }

    override fun onSpamResultPackageListUpdated() {
        refreshDeviceCount()
        if (AppContext.getBluetoothLeScanService().getSpamPackageScanResultList().isEmpty()) {
            notifyOnNewSpam = true
        }
        updateNotification(
            getString(R.string.spam_detecting_title),
            getString(R.string.spam_detecting_text),
            true, 2
        )
    }
}
