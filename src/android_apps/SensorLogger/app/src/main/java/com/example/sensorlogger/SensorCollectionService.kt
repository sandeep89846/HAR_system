package com.example.sensorlogger // Use your actual package name

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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock // Added for throttling
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Added for local broadcasts
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SensorCollectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // --- State & File ---
    @Volatile private var isServiceRunning = false
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Data Storage (Volatile as accessed by sensor thread and potentially sampling thread) ---
    @Volatile private var latestAccelData = FloatArray(3) { 0f }
    @Volatile private var latestGyroData = FloatArray(3) { 0f }

    // --- Sampling & Throttling ---
    private var samplingExecutor: ScheduledExecutorService? = null
    private val SAMPLING_PERIOD_MS = 50L // 20 Hz (for file writing)
    private val BROADCAST_THROTTLE_MS = 100L // ~10 Hz (for UI updates) - Adjust as needed
    @Volatile private var lastBroadcastTime = 0L // For throttling UI updates

    // --- Sensor Event Handling ---
    private var sensorHandlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // --- Broadcast ---
    private lateinit var localBroadcastManager: LocalBroadcastManager // Added

    companion object {
        private const val TAG = "SensorService"
        const val ACTION_START_SERVICE = "com.example.sensorlogger.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.sensorlogger.action.STOP_SERVICE"
        const val EXTRA_ACTIVITY_NAME = "com.example.sensorlogger.extra.ACTIVITY_NAME"
        const val EXTRA_SUBJECT_ID = "com.example.sensorlogger.extra.SUBJECT_ID"
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        private const val NOTIFICATION_ID = 1

        // --- Broadcast Actions and Extras (match these in MainActivity) ---
        const val ACTION_SENSOR_UPDATE = "com.example.sensorlogger.action.SENSOR_UPDATE"
        const val EXTRA_ACCEL_X = "com.example.sensorlogger.extra.ACCEL_X"
        const val EXTRA_ACCEL_Y = "com.example.sensorlogger.extra.ACCEL_Y"
        const val EXTRA_ACCEL_Z = "com.example.sensorlogger.extra.ACCEL_Z"
        const val EXTRA_GYRO_X = "com.example.sensorlogger.extra.GYRO_X"
        const val EXTRA_GYRO_Y = "com.example.sensorlogger.extra.GYRO_Y"
        const val EXTRA_GYRO_Z = "com.example.sensorlogger.extra.GYRO_Z"

        const val ACTION_RECORDING_STOPPED = "com.example.sensorlogger.action.RECORDING_STOPPED"
        const val EXTRA_FILENAME = "com.example.sensorlogger.extra.FILENAME"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        localBroadcastManager = LocalBroadcastManager.getInstance(this) // Initialize Broadcaster

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Initialize Sensor Handler Thread
        sensorHandlerThread = HandlerThread("SensorServiceHandlerThread").apply {
            start()
            sensorHandler = Handler(looper)
        }

        // Acquire a partial wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:WakeLock")
        wakeLock?.setReferenceCounted(false)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand, Action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                if (isServiceRunning) {
                    Log.w(TAG, "Start command received, but service already running.")
                    return START_STICKY
                }
                val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME) ?: "UnknownActivity"
                val subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: "UnknownSubject"
                startRecordingInternal(activityName, subjectId)
            }
            ACTION_STOP_SERVICE -> {
                stopRecordingInternal()
                stopSelf() // Stop the service itself after cleanup
            }
            else -> {
                Log.w(TAG, "Unknown or null action received: ${intent?.action}")
                // Decide how to handle - maybe stop if state is unexpected?
                if (!isServiceRunning) stopSelf() // Stop if not running and unknown action
            }
        }

        // If the service is killed, restart it unless explicitly stopped
        return START_STICKY
    }

    private fun startRecordingInternal(activity: String, subject: String) {
        if (isServiceRunning) return

        Log.i(TAG, "Attempting to start recording for $activity - $subject")

        // Acquire WakeLock
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(TimeUnit.HOURS.toMillis(2)) // Acquire with a timeout (e.g., 2 hours)
            Log.i(TAG, "WakeLock acquired.")
        } else {
            Log.w(TAG, "WakeLock already held or null during start attempt.")
        }

        // 1. Create Filename and File
        val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val currentTime = timestampFormat.format(Date())
        val filename = "${activity}_${subject}_${currentTime}.csv"
        val storageDir = getExternalFilesDir(null) // App-specific storage
        if (storageDir == null || (!storageDir.exists() && !storageDir.mkdirs())) {
            Log.e(TAG, "Cannot create storage directory: ${storageDir?.absolutePath}")
            stopSelf() // Cannot proceed without storage
            return
        }
        currentFile = File(storageDir, filename)
        Log.i(TAG, "Recording to: ${currentFile?.absolutePath}")

        try {
            // 2. Initialize Writer
            writer = BufferedWriter(FileWriter(currentFile))
            writer?.append("timestamp_ms,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z\n") // Header

            // 3. Register Sensor Listeners on the dedicated handler thread
            val accRegistered = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
            val gyroRegistered = sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
            Log.i(TAG, "Sensor listeners registered: Accel=$accRegistered, Gyro=$gyroRegistered")
            if (!accRegistered || !gyroRegistered) {
                Log.w(TAG, "One or more sensors failed to register!")
                // Consider stopping if sensors are critical
            }

            // 4. Start Sampling Timer for file writing
            samplingExecutor = Executors.newSingleThreadScheduledExecutor()
            samplingExecutor?.scheduleAtFixedRate({
                logSensorData() // This runs on the executor's thread
            }, 0, SAMPLING_PERIOD_MS, TimeUnit.MILLISECONDS)

            // 5. Start Foreground Service
            startForeground(NOTIFICATION_ID, createNotification("Recording: $activity"))
            isServiceRunning = true
            lastBroadcastTime = 0L // Reset broadcast timer on start
            Log.i(TAG, "Service started recording successfully.")

            // Optional: Send a broadcast to notify Activity that recording definitely started
            // localBroadcastManager.sendBroadcast(Intent("com.example.sensorlogger.RECORDING_STARTED"))

        } catch (e: IOException) {
            Log.e(TAG, "IOException starting recording in service", e)
            stopRecordingInternal() // Clean up on error
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during service startRecording", e)
            stopRecordingInternal()
            stopSelf()
        }
    }

    private fun stopRecordingInternal() {
        if (!isServiceRunning) {
            Log.w(TAG, "Stop recording called, but service wasn't running.")
            return
        }
        Log.i(TAG, "Stopping recording in service.")

        // Store filename *before* cleanup potentially nullifies currentFile
        val stoppedFilename = currentFile?.name

        isServiceRunning = false // Set state first to prevent further processing

        // Stop Sampling Timer (gracefully)
        samplingExecutor?.shutdown()
        try {
            if (samplingExecutor?.awaitTermination(200, TimeUnit.MILLISECONDS) == false) {
                Log.w(TAG, "Sampling executor did not terminate gracefully, forcing shutdown.")
                samplingExecutor?.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for sampling executor shutdown.")
            samplingExecutor?.shutdownNow()
            Thread.currentThread().interrupt() // Re-interrupt thread
        } finally {
            samplingExecutor = null
        }


        // Unregister Sensor Listeners
        Log.d(TAG, "Unregistering sensor listeners.")
        sensorManager.unregisterListener(this)

        // Close File Writer
        try {
            writer?.flush()
            writer?.close()
            Log.i(TAG, "File closed successfully: $stoppedFilename")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing writer in service", e)
        } finally {
            writer = null // Ensure writer is nullified
        }

        // --- Send Stop Confirmation Broadcast ---
        val stopIntent = Intent(ACTION_RECORDING_STOPPED).apply {
            putExtra(EXTRA_FILENAME, stoppedFilename) // Send filename even if null
        }
        localBroadcastManager.sendBroadcast(stopIntent)
        Log.d(TAG, "Sent recording stopped broadcast. File: $stoppedFilename")
        // ----------------------------------------

        currentFile = null // Nullify file reference *after* sending broadcast

        // Release WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "WakeLock released.")
        } else {
            Log.w(TAG, "Attempted to release WakeLock but it wasn't held or was null.")
        }

        // Stop being a foreground service (removes notification)
        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.i(TAG, "Service stopped recording procedures complete.")
    }


    // --- Data Logging (Runs on samplingExecutor thread) ---
    private fun logSensorData() {
        // Check isServiceRunning first to avoid writing after stop initiated but before writer is closed
        if (!isServiceRunning || writer == null) return

        val timestamp = System.currentTimeMillis()
        // Access volatile fields directly - okay for reading primitives/references here
        val ax = latestAccelData[0]
        val ay = latestAccelData[1]
        val az = latestAccelData[2]
        val gx = latestGyroData[0]
        val gy = latestGyroData[1]
        val gz = latestGyroData[2]
        val dataLine = "$timestamp,$ax,$ay,$az,$gx,$gy,$gz\n"

        try {
            writer?.append(dataLine)
            // Avoid flushing frequently unless necessary, rely on BufferedWriter's buffer
            // writer?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing data line in service", e)
            // Consider stopping the service if file writing fails persistently
            // stopRecordingInternal()
            // stopSelf()
        }
    }


    // --- SensorEventListener Callbacks (Run on sensorHandlerThread) ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isServiceRunning) return // Check running state

        val currentTime = SystemClock.elapsedRealtime() // Use elapsedRealtime for intervals

        // Update latest data based on sensor type
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> latestAccelData = event.values.clone()
            Sensor.TYPE_GYROSCOPE -> latestGyroData = event.values.clone()
            else -> return // Don't process or broadcast other sensor types
        }

        // --- Throttle and Broadcast Sensor Data for UI ---
        if (currentTime - lastBroadcastTime > BROADCAST_THROTTLE_MS) {
            lastBroadcastTime = currentTime
            val intent = Intent(ACTION_SENSOR_UPDATE).apply {
                // Access volatile fields safely here for broadcast
                putExtra(EXTRA_ACCEL_X, latestAccelData[0])
                putExtra(EXTRA_ACCEL_Y, latestAccelData[1])
                putExtra(EXTRA_ACCEL_Z, latestAccelData[2])
                putExtra(EXTRA_GYRO_X, latestGyroData[0])
                putExtra(EXTRA_GYRO_Y, latestGyroData[1])
                putExtra(EXTRA_GYRO_Z, latestGyroData[2])
            }
            localBroadcastManager.sendBroadcast(intent)
            // Log.v(TAG, "Sent sensor update broadcast") // Verbose logging, disable if noisy
        }
        // ------------------------------------------
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.i(TAG, "Accuracy changed for ${sensor?.name} to $accuracy")
        // You could potentially log this or notify the user if accuracy drops significantly
    }


    // --- Service Lifecycle & Binding ---
    override fun onBind(intent: Intent?): IBinder? {
        // Return null because we are using startService/stopService and broadcasts, not binding
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        stopRecordingInternal() // Ensure everything stops and cleans up if not already stopped

        // Stop the sensor handler thread
        sensorHandlerThread?.quitSafely()
        try {
            sensorHandlerThread?.join(500) // Wait briefly for thread to finish
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while joining sensor handler thread", e)
            Thread.currentThread().interrupt() // Re-interrupt
        } finally {
            sensorHandlerThread = null
            sensorHandler = null
        }


        // Final check on WakeLock release (should have been released in stopRecordingInternal)
        if (wakeLock?.isHeld == true) {
            Log.w(TAG, "WakeLock still held in onDestroy, releasing.")
            wakeLock?.release()
        }
        wakeLock = null // Ensure reference is cleared

        Log.i(TAG, "Service fully destroyed.")
        super.onDestroy()
    }


    // --- Notification Handling ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sensor Collection Service Channel",
                NotificationManager.IMPORTANCE_LOW // Low importance = less intrusive status bar icon
            ).apply {
                description = "Channel for Sensor Logger foreground service notification"
                // Configure channel further if needed (e.g., disable sound)
                // setSound(null, null)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    private fun createNotification(contentText: String): Notification {
        // Intent to open the MainActivity when the notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            // Flags to bring existing activity task to front or start new one
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, // Request code 0 for the main content intent
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE // Recommended flags
        )

        // Add Stop Action to Notification
        val stopServiceIntent = Intent(this, SensorCollectionService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        // Use a different request code for the action pending intent
        val stopPendingIntent = PendingIntent.getService(
            this,
            1, // Request code 1 for stop action
            stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Sensor Logger Active")
            .setContentText(contentText)
            // IMPORTANT: Replace with your actual small icon drawable
            .setSmallIcon(R.drawable.ic_launcher_foreground) // CHANGE THIS!
            .setContentIntent(pendingIntent) // Intent when notification body is tapped
            .setOngoing(true) // Makes it non-dismissable by swiping
            .setOnlyAlertOnce(true) // Don't vibrate/sound for ongoing notification updates
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent) // Add stop button action
            .build()
    }
}