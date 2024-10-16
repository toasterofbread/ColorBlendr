package com.drdisagree.colorblendr.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.IBinder
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.drdisagree.colorblendr.BuildConfig
import com.drdisagree.colorblendr.ColorBlendr.Companion.appContext
import com.drdisagree.colorblendr.ColorBlendr.Companion.rootConnection
import com.drdisagree.colorblendr.R
import com.drdisagree.colorblendr.common.Const
import com.drdisagree.colorblendr.common.Const.ALLOW_EXTERNAL_ACCESS
import com.drdisagree.colorblendr.common.Const.MONET_COLOR_EXTERNAL_OVERLAY_TIMEOUT_SECONDS
import com.drdisagree.colorblendr.common.Const.MONET_SEED_COLOR_EXTERNAL_OVERLAY
import com.drdisagree.colorblendr.common.Const.MONET_SEED_COLOR_EXTERNAL_OVERLAY_ENABLED
import com.drdisagree.colorblendr.common.Const.MONET_SEED_COLOR_EXTERNAL_OVERLAY_OWNER
import com.drdisagree.colorblendr.common.Const.workingMethod
import com.drdisagree.colorblendr.config.RPrefs.getBoolean
import com.drdisagree.colorblendr.config.RPrefs.getInt
import com.drdisagree.colorblendr.config.RPrefs.getString
import com.drdisagree.colorblendr.config.RPrefs.putBoolean
import com.drdisagree.colorblendr.config.RPrefs.putInt
import com.drdisagree.colorblendr.config.RPrefs.putString
import com.drdisagree.colorblendr.provider.RootConnectionProvider
import com.drdisagree.colorblendr.provider.ShizukuConnectionProvider
import com.drdisagree.colorblendr.utils.ColorUtil.getAccentColor
import com.drdisagree.colorblendr.utils.OverlayManager
import com.drdisagree.colorblendr.utils.ShizukuUtil.bindUserService
import com.drdisagree.colorblendr.utils.ShizukuUtil.getUserServiceArgs
import com.drdisagree.colorblendr.utils.ShizukuUtil.hasShizukuPermission
import com.drdisagree.colorblendr.utils.ShizukuUtil.isShizukuAvailable
import com.drdisagree.colorblendr.utils.SystemUtil.getScreenRotation
import com.drdisagree.colorblendr.utils.SystemUtil.sensorEventListener
import com.drdisagree.colorblendr.utils.annotations.TestingOnly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.seconds

class AutoStartService : Service() {

    private var notificationManager: NotificationManager? = null
    private val seedOverlayTimeoutCoroutineScope: CoroutineScope = CoroutineScope(Job())

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        isRunning = true
        registerSystemServices()
        createNotificationChannel()
        showNotification()
        registerReceivers()

        if (BroadcastListener.lastOrientation == -1) {
            BroadcastListener.lastOrientation = getScreenRotation(
                this
            )
        }

        putBoolean(MONET_SEED_COLOR_EXTERNAL_OVERLAY_ENABLED, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        setupSystemUIRestartListener()

        if (isTestingService) {
            // Testing purposes only
            startTimer(this)
        }

        if (getBoolean(ALLOW_EXTERNAL_ACCESS, false)) {
            val action: String? = intent?.action
            val actionPrefix: String = "com.drdisagree.colorblendr."

            if (action?.startsWith(actionPrefix) == true) {
                handleActionIntent(action.drop(actionPrefix.length), intent)
            }
        }

        return START_STICKY
    }

    private fun handleActionIntent(action: String, intent: Intent) {
        val owner: String? = intent.getStringExtra("owner")
        if (owner.isNullOrBlank()) {
            return
        }

        when (action) {
            "SET_PRIMARY_COLOR" -> {
                if (!intent.hasExtra("set_color")) {
                    return
                }

                val color: Int = intent.getIntExtra("set_color", 0)
                if (color == getInt(MONET_SEED_COLOR_EXTERNAL_OVERLAY) && getBoolean(MONET_SEED_COLOR_EXTERNAL_OVERLAY_ENABLED)) {
                    return
                }

                putString(MONET_SEED_COLOR_EXTERNAL_OVERLAY_OWNER, owner)
                putInt(MONET_SEED_COLOR_EXTERNAL_OVERLAY, color)
                putBoolean(MONET_SEED_COLOR_EXTERNAL_OVERLAY_ENABLED, true)

                GlobalScope.launch {
                    OverlayManager.applyFabricatedColors(appContext)
                }

                startSeedOverlayTimeout()
            }
            "RESET_PRIMARY_COLOR" -> {
                OverlayManager.removeFabricatedColors(appContext)
            }
            "SERVICE_HEARTBEAT" -> {
                if (owner == getString(MONET_SEED_COLOR_EXTERNAL_OVERLAY_OWNER)) {
                    startSeedOverlayTimeout()
                }
            }
        }
    }

    private fun startSeedOverlayTimeout() {
        if (getString(MONET_SEED_COLOR_EXTERNAL_OVERLAY_OWNER) == null) {
            return
        }

        val timeoutSeconds: Int = getInt(MONET_COLOR_EXTERNAL_OVERLAY_TIMEOUT_SECONDS, 10)
        if (timeoutSeconds <= 0) {
            return
        }

        seedOverlayTimeoutCoroutineScope.coroutineContext.cancelChildren()
        seedOverlayTimeoutCoroutineScope.launch {
            delay(timeoutSeconds.seconds)

            putBoolean(MONET_SEED_COLOR_EXTERNAL_OVERLAY_ENABLED, false)
            putString(MONET_SEED_COLOR_EXTERNAL_OVERLAY_OWNER, "")

            OverlayManager.applyFabricatedColors(appContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isRunning = false
        Log.i(TAG, "onDestroy: Service is destroyed :(")

        seedOverlayTimeoutCoroutineScope.cancel()

        try {
            unregisterReceiver(myReceiver)
        } catch (ignored: Exception) {
            // Receiver was probably never registered
        }

        val broadcastIntent = Intent(
            this,
            RestartBroadcastReceiver::class.java
        )
        sendBroadcast(broadcastIntent)

        if (isTestingService) {
            // Testing purposes only
            stopTimer()
        }
    }

    private fun registerSystemServices() {
        if (notificationManager == null) {
            notificationManager = getSystemService(NotificationManager::class.java)
        }

        if (sensorManager == null) {
            sensorManager = getSystemService(SensorManager::class.java)

            if (sensorManager != null) {
                sensorManager!!.registerListener(
                    sensorEventListener,
                    sensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_UI
                )
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.background_service_notification_channel_title),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = getString(R.string.background_service_notification_channel_text)
        notificationManager!!.createNotificationChannel(channel)
    }

    private fun showNotification() {
        val notificationIntent = Intent()
        notificationIntent.setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        notificationIntent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        notificationIntent.putExtra(Settings.EXTRA_CHANNEL_ID, NOTIFICATION_CHANNEL_ID)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setContentTitle(getString(R.string.background_service_notification_title))
            .setContentText(getString(R.string.background_service_notification_text))
            .setContentIntent(pendingIntent)
            .setSound(null, AudioManager.STREAM_NOTIFICATION)
            .setColor(getAccentColor(this))
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun registerReceivers() {
        val intentFilterWithoutScheme = IntentFilter()
        intentFilterWithoutScheme.addAction(Intent.ACTION_WALLPAPER_CHANGED)
        intentFilterWithoutScheme.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        intentFilterWithoutScheme.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilterWithoutScheme.addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
        intentFilterWithoutScheme.addAction(Intent.ACTION_PACKAGE_ADDED)
        intentFilterWithoutScheme.addAction(Intent.ACTION_PACKAGE_REMOVED)
        intentFilterWithoutScheme.addAction(Intent.ACTION_PACKAGE_REPLACED)

        val intentFilterWithScheme = IntentFilter()
        intentFilterWithScheme.addAction(Intent.ACTION_PACKAGE_ADDED)
        intentFilterWithScheme.addAction(Intent.ACTION_PACKAGE_REMOVED)
        intentFilterWithScheme.addAction(Intent.ACTION_PACKAGE_REPLACED)
        intentFilterWithScheme.addDataScheme("package")

        registerReceiver(myReceiver, intentFilterWithoutScheme)
        registerReceiver(myReceiver, intentFilterWithScheme)
    }

    private fun setupSystemUIRestartListener() {
        if (workingMethod == Const.WorkMethod.ROOT &&
            RootConnectionProvider.isNotConnected
        ) {
            RootConnectionProvider.builder(appContext)
                .onSuccess { initSystemUIRestartListener() }
                .run()
        } else if (workingMethod == Const.WorkMethod.SHIZUKU &&
            ShizukuConnectionProvider.isNotConnected &&
            isShizukuAvailable &&
            hasShizukuPermission(appContext)
        ) {
            bindUserService(
                getUserServiceArgs(ShizukuConnection::class.java),
                ShizukuConnectionProvider.serviceConnection
            )
        } else if (workingMethod == Const.WorkMethod.ROOT) {
            initSystemUIRestartListener()
        }
    }

    private fun initSystemUIRestartListener() {
        try {
            rootConnection?.setSystemUIRestartListener()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to set SystemUI restart listener", e)
        }
    }

    @TestingOnly
    private val testBackgroundService = false

    @TestingOnly
    private val isTestingService = BuildConfig.DEBUG && testBackgroundService

    @TestingOnly
    var counter: Int = 0

    @TestingOnly
    private var timer: Timer? = null

    init {
        isRunning = false
        myReceiver = BroadcastListener()
    }

    @TestingOnly
    fun startTimer(context: Context) {
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                Log.i(TEST_TAG, "Timer is running " + counter++)
                broadcastActionTest(context, counter.toString())
            }
        }, 1000, 1000)
    }

    @TestingOnly
    fun stopTimer() {
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }
    }

    companion object {
        private val TAG: String = AutoStartService::class.java.simpleName
        private var isRunning = false
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "Background Service"
        private lateinit var myReceiver: BroadcastListener
        var sensorManager: SensorManager? = null

        @JvmStatic
        val isServiceNotRunning: Boolean
            get() = !isRunning

        /*
    * The following fields and methods are for testing purposes only
    */
        @TestingOnly
        private val TEST_TAG = AutoStartService::class.java.simpleName + "_TEST"

        @TestingOnly
        private val packageName: String = appContext.packageName

        @TestingOnly
        val ACTION_FOO: String = "$packageName.FOO"

        @TestingOnly
        val EXTRA_PARAM_A: String = "$packageName.PARAM_A"

        @TestingOnly
        fun broadcastActionTest(context: Context, param: String?) {
            val intent = Intent(ACTION_FOO)
            intent.putExtra(EXTRA_PARAM_A, param)
            val bm = LocalBroadcastManager.getInstance(context)
            bm.sendBroadcast(intent)
        }
    }
}
