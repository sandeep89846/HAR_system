package com.example.harclassifier // Adjust package name

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.harclassifier.R // Ensure R is imported correctly
import com.example.harclassifier.MainViewModel
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference



class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private var socket: Socket? = null
    private var serverUrl: String? = null

    // Use SENSOR_DELAY_GAME (~50Hz or platform default)
    private val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME
    @Volatile // Ensure visibility across threads
    private var isServiceRunning = false

    // Sensor Event Handling Thread
    private var sensorHandlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // --- Sending Handler ---
    private var sendingHandlerThread: HandlerThread? = null
    private var sendingHandler: Handler? = null
    private val SEND_INTERVAL_MS = 50L // Target 20Hz
    @Volatile // Ensure visibility across threads
    private var isSendingScheduled = false
    private var nextSendTime = 0L // Track next execution time for postAtTime


    // --- Thread-safe storage for latest sensor values ---
    private val latestAccel = AtomicReference<FloatArray>(null)
    private val latestGyro = AtomicReference<FloatArray>(null)


    // WakeLock
    private var wakeLock: PowerManager.WakeLock? = null

    // For sending updates to the Activity/ViewModel
    private lateinit var broadcaster: LocalBroadcastManager

    // Throttle UI updates if needed
    private var lastUiUpdateTime = 0L
    private val UI_UPDATE_INTERVAL_MS = 200 // Update UI max 5 times per second

    companion object {
        const val ACTION_START_SERVICE = "com.example.harclassifier.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.harclassifier.action.STOP_SERVICE"
        const val EXTRA_SERVER_URL = "com.example.harclassifier.extra.SERVER_URL"

        const val BROADCAST_STATUS_UPDATE = "com.example.harclassifier.broadcast.STATUS"
        const val BROADCAST_PREDICTION_UPDATE = "com.example.harclassifier.broadcast.PREDICTION"
        const val BROADCAST_SENSOR_UPDATE = "com.example.harclassifier.broadcast.SENSORS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PREDICTION = "extra_prediction"
        const val EXTRA_ACCEL_STR = "extra_accel"
        const val EXTRA_GYRO_STR = "extra_gyro"


        private const val TAG = "SensorService"
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        broadcaster = LocalBroadcastManager.getInstance(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        sensorHandlerThread = HandlerThread("SensorServiceHandlerThread", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply {
            start()
            sensorHandler = Handler(looper)
        }

        // --- Sending Handler Thread ---
        sendingHandlerThread = HandlerThread("SendingHandlerThread").apply {
            start()
            // Get looper only after thread has started
            val threadLooper = looper // Capture the looper
            if (threadLooper != null) {
                sendingHandler = Handler(threadLooper)
                Log.i(TAG, "SendingHandlerThread started. Looper: $threadLooper")
            } else {
                Log.e(TAG, "SendingHandlerThread looper is null after start!")
                // Handle error - maybe stop service?
            }
        }

        // Acquire WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:WakeLock")
        wakeLock?.setReferenceCounted(false)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand - Action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                if (isServiceRunning) {
                    Log.w(TAG, "Service already running.")
                    return START_STICKY
                }
                serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                if (serverUrl.isNullOrBlank()) {
                    Log.e(TAG, "Server URL is missing, stopping service.")
                    broadcastStatus(ConnectionStatus.ERROR.name, "Missing URL")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSensorCollection(serverUrl!!)
            }
            ACTION_STOP_SERVICE -> {
                stopSensorCollection()
                stopSelf()
            }
        }
        return START_STICKY // Restart service if killed
    }

    private fun startSensorCollection(url: String) {
        if (accelerometer == null || gyroscope == null) {
            Log.e(TAG, "Required sensors not available.")
            broadcastStatus(ConnectionStatus.ERROR.name, "Sensors missing")
            stopSelf()
            return
        }
        if (sendingHandler == null) {
            Log.e(TAG, "Sending Handler is null, cannot start collection.")
            broadcastStatus(ConnectionStatus.ERROR.name, "Internal Handler Error")
            stopSelf()
            return
        }


        // Acquire WakeLock
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(TimeUnit.HOURS.toMillis(4)) // Adjust timeout as needed
            Log.i(TAG, "WakeLock acquired.")
        }

        isServiceRunning = true
        broadcastIsRunning(true) // Notify Activity/ViewModel

        // Start Foreground
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        broadcastStatus(ConnectionStatus.CONNECTING.name)


        initializeSocket(url)

        // Reset latest values
        latestAccel.set(null)
        latestGyro.set(null)

        // Register listeners on the dedicated handler thread
        val accelRegistered = sensorManager.registerListener(this, accelerometer, SENSOR_DELAY, sensorHandler)
        val gyroRegistered = sensorManager.registerListener(this, gyroscope, SENSOR_DELAY, sensorHandler)
        Log.i(TAG, "Sensor listeners registered. Accel: $accelRegistered, Gyro: $gyroRegistered")

        // --- Schedule the periodic sender ---
        scheduleSender() // Use the new scheduling method
        // isSendingScheduled is set within scheduleSender now
    }

    private fun stopSensorCollection() {
        if (!isServiceRunning) return
        Log.i(TAG, "Stopping sensor collection.")
        isServiceRunning = false // Set state first
        broadcastIsRunning(false) // Notify Activity/ViewModel

        sensorManager.unregisterListener(this)
        Log.i(TAG, "Sensor listeners unregistered.")

        // --- Stop the periodic sender ---
        if (isSendingScheduled) {
            sendingHandler?.removeCallbacks(sendDataRunnable) // Remove any pending posts
            isSendingScheduled = false
            Log.i(TAG,"Periodic sender stopped.")
        }

        // Disconnect Socket
        socket?.disconnect()
        socket?.off() // Remove listeners
        socket = null
        Log.i(TAG, "Socket disconnected and listeners removed.")

        // Release WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "WakeLock released.")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Foreground service stopped.")
        broadcastStatus(ConnectionStatus.DISCONNECTED.name)

    }

    private fun initializeSocket(url: String) {
        try {
            val opts = IO.Options.builder().build()
            socket = IO.socket(url, opts)

            // Setup Listeners
            socket?.on(Socket.EVENT_CONNECT, onConnect)
            socket?.on(Socket.EVENT_DISCONNECT, onDisconnect)
            socket?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
            socket?.on("prediction", onPrediction) // Listen for predictions

            socket?.connect()
            Log.i(TAG, "Attempting to connect to socket at $url")

        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid Server URL: $url", e)
            broadcastStatus(ConnectionStatus.ERROR.name, "Invalid URL")
            stopSensorCollection()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Socket initialization error", e)
            broadcastStatus(ConnectionStatus.ERROR.name, "Socket Init Error")
            stopSensorCollection()
            stopSelf()
        }
    }

    // --- Socket Event Handlers ---
    private val onConnect = Emitter.Listener {
        Log.i(TAG, "Socket Connected!")
        updateNotification("Connected & Recording") // Update own notification
        broadcastStatus(ConnectionStatus.CONNECTED.name)
    }

    private val onDisconnect = Emitter.Listener { args ->
        val reason = args.firstOrNull()?.toString() ?: "Unknown reason"
        Log.w(TAG, "Socket Disconnected! Reason: $reason")
        updateNotification("Disconnected") // Update own notification
        broadcastStatus(ConnectionStatus.DISCONNECTED.name, reason)
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.firstOrNull()?.toString() ?: "Unknown error"
        Log.e(TAG, "Socket Connection Error! Error: $error")
        updateNotification("Connection Error") // Update own notification
        broadcastStatus(ConnectionStatus.ERROR.name, error)
        stopSensorCollection() // Stop service on connection error
        stopSelf()
    }

    private val onPrediction = Emitter.Listener { args ->
        if (args.isNotEmpty() && args[0] is JSONObject) {
            val predictionData = args[0] as JSONObject
            val predictedActivity = predictionData.optString("activity", "Unknown")
            updateNotification("Prediction: $predictedActivity") // Update own notification
            broadcastPrediction(predictedActivity) // Broadcast prediction to Activity
        } else {
            Log.w(TAG, "Received invalid prediction data format: ${args.firstOrNull()}")
        }
    }

    // --- SensorEventListener ---
    override fun onSensorChanged(event: SensorEvent?) {
        // Added logging previously here, can be kept for debugging
        // val sensorName = event?.sensor?.name ?: "Unknown"
        // val valuesStr = event?.values?.joinToString(", ") ?: "null"
        // Log.d(TAG, "onSensorChanged - Sensor: $sensorName, Values: $valuesStr, Running: $isServiceRunning")

        if (!isServiceRunning || event == null) return

        // Store the latest value thread-safely
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel.set(event.values.clone())
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro.set(event.values.clone())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Log if desired
        // Log.i(TAG, "Accuracy changed for ${sensor?.name}: $accuracy")
    }

    // --- Periodic Sender ---
    private val sendDataRunnable = object : Runnable {
        override fun run() {
            val currentTime = SystemClock.uptimeMillis()
            // Log.d(TAG, "sendDataRunnable executing at $currentTime (Scheduled: $nextSendTime)")

            // --- Modified Check ---
            // Only stop scheduling entirely if the service itself has been stopped.
            if (!isServiceRunning) {
                Log.w(TAG, "sendDataRunnable: Stopping schedule (service stopped).")
                isSendingScheduled = false
                return
            }

            // Check socket connection status *before* trying to send
            if (socket?.connected() != true) {
                Log.d(TAG, "sendDataRunnable: Socket not connected yet, skipping send.")
                // --- STILL RESCHEDULE ---
                scheduleNextSend() // Try again after the interval
                return // Don't proceed to get data or send
            }
            // --- End Modified Check ---


            // Socket is connected if we reach here
            val currentAccel = latestAccel.get()
            val currentGyro = latestGyro.get()

            val accelStr = currentAccel?.joinToString(", ") ?: "null"
            val gyroStr = currentGyro?.joinToString(", ") ?: "null"
            Log.d(TAG, "sendDataRunnable - Accel: $accelStr, Gyro: $gyroStr")

            if (currentAccel != null && currentGyro != null) {
                val timestamp = System.currentTimeMillis()
                val data = JSONObject()
                try {
                    // ... (put data into JSON object - same as before) ...
                    data.put("timestamp", timestamp)
                    data.put("accel_x", currentAccel[0])
                    data.put("accel_y", currentAccel[1])
                    data.put("accel_z", currentAccel[2])
                    data.put("gyro_x", currentGyro[0])
                    data.put("gyro_y", currentGyro[1])
                    data.put("gyro_z", currentGyro[2])


                    socket?.emit("sensor_data", data)

                    // ... (broadcast sensor update for UI - same as before) ...
                    val uiCurrentTime = SystemClock.elapsedRealtime()
                    if (uiCurrentTime - lastUiUpdateTime > UI_UPDATE_INTERVAL_MS) {
                        val uiAccelStr = "%.2f, %.2f, %.2f".format(currentAccel[0], currentAccel[1], currentAccel[2])
                        val uiGyroStr = "%.2f, %.2f, %.2f".format(currentGyro[0], currentGyro[1], currentGyro[2])
                        broadcastSensorUpdate(accel = uiAccelStr, gyro = uiGyroStr)
                        lastUiUpdateTime = uiCurrentTime
                    }


                } catch (e: Exception) {
                    Log.e(TAG, "Error creating/sending combined sensor data JSON", e)
                }
            } else {
                Log.d(TAG, "sendDataRunnable - Skipping send, one or both sensors null.")
                // Still need to reschedule even if sensors aren't ready yet
            }

            // --- Reschedule self using postAtTime ---
            scheduleNextSend() // Call the rescheduling function
        }
    }

    // Changed scheduleSender to use postAtTime for initial post
    private fun scheduleSender() {
        if (sendingHandler == null) {
            Log.e(TAG, "Cannot schedule sender, sendingHandler is null!")
            return
        }
        Log.i(TAG, "scheduleSender called. Posting first sendDataRunnable.")
        // Post the first one to run shortly after start, using uptimeMillis
        nextSendTime = SystemClock.uptimeMillis() + SEND_INTERVAL_MS // Schedule first run
        val success = sendingHandler?.postAtTime(sendDataRunnable, nextSendTime)
        Log.i(TAG,"Posted initial sendDataRunnable using postAtTime. Success: $success")
        if (success == true) {
            isSendingScheduled = true
            Log.i(TAG,"Periodic sender scheduled.")
        } else {
            Log.e(TAG, "Failed to post initial sendDataRunnable!")
            isSendingScheduled = false
            // Consider stopping service?
        }
    }

    // New function to handle rescheduling with postAtTime
    private fun scheduleNextSend() {
        if (!isServiceRunning || sendingHandler == null) {
            // Don't reschedule if stopped or handler is gone
            isSendingScheduled = false
            return
        }

        // Calculate next time based on the *intended* interval from the last scheduled time
        nextSendTime += SEND_INTERVAL_MS

        // Ensure nextSendTime isn't too far in the past (adjust if execution lagged)
        val currentTime = SystemClock.uptimeMillis()
        if (nextSendTime < currentTime) {
            // Log.w(TAG, "Execution lagged, scheduling next immediately.")
            nextSendTime = currentTime // Schedule immediately if we're behind
        }

        // Use postAtTime for more precise scheduling relative to uptime clock
        sendingHandler?.postAtTime(sendDataRunnable, nextSendTime)
        // isSendingScheduled remains true as we intend to continue
    }


    // --- Service Lifecycle & Binding ---
    override fun onBind(intent: Intent?): IBinder? {
        return null // Not using binding
    }

    override fun onDestroy() {
        Log.w(TAG, "Service onDestroy")
        stopSensorCollection() // Ensure cleanup

        // Stop handler threads
        sendingHandlerThread?.quitSafely()
        sensorHandlerThread?.quitSafely()
        try {
            sendingHandlerThread?.join(100)
            sensorHandlerThread?.join(100)
        } catch (e: InterruptedException) { Log.e(TAG, "Interrupted joining handler threads") }

        super.onDestroy()
    }

    // --- Notification ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sensor Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.description = "Channel for HAR Sensor Service"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val stopServiceIntent = Intent(this, SensorService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopServiceIntent, pendingIntentFlags)


        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("HAR Classification Active")
            .setContentText(contentText)
            // Ensure R.drawable.ic_stat_sensors exists and is white/transparent
            .setSmallIcon(R.drawable.ic_stat_sensors)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        try { // Add try-catch just in case manager is null unexpectedly
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    // --- Broadcasting Helpers ---
    private fun broadcastStatus(status: String, message: String? = null) {
        val intent = Intent(BROADCAST_STATUS_UPDATE)
        intent.putExtra(EXTRA_STATUS, status)
        message?.let { intent.putExtra("message", it) }
        broadcaster.sendBroadcast(intent)
    }
    private fun broadcastIsRunning(isRunning: Boolean) {
        val intent = Intent(BROADCAST_STATUS_UPDATE)
        intent.putExtra("is_running", isRunning)
        broadcaster.sendBroadcast(intent)
    }

    private fun broadcastPrediction(prediction: String) {
        val intent = Intent(BROADCAST_PREDICTION_UPDATE)
        intent.putExtra(EXTRA_PREDICTION, prediction)
        broadcaster.sendBroadcast(intent)
    }

    private fun broadcastSensorUpdate(accel: String? = null, gyro: String? = null) {
        val intent = Intent(BROADCAST_SENSOR_UPDATE)
        accel?.let { intent.putExtra(EXTRA_ACCEL_STR, it) }
        gyro?.let { intent.putExtra(EXTRA_GYRO_STR, it) }
        broadcaster.sendBroadcast(intent)
    }
}