package com.example.sensorlogger // Replace with your actual package name

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter // Add IntentFilter import
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View // Add View import
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Add LocalBroadcastManager import
import java.util.Locale // Add Locale import

class MainActivity : AppCompatActivity() {

    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button
    private lateinit var textViewStatus: TextView
    // New TextView references for sensor data table
    private lateinit var textViewAccelX: TextView
    private lateinit var textViewAccelY: TextView
    private lateinit var textViewAccelZ: TextView
    private lateinit var textViewGyroX: TextView
    private lateinit var textViewGyroY: TextView
    private lateinit var textViewGyroZ: TextView
    private lateinit var textViewSaveConfirmation: TextView

    // Keep track of service state simply (as before)
    // Consider making this non-static and managing state better if Activity recreation issues arise
    companion object {
        @Volatile
        var isServiceRunning = false // Check service state directly if possible in future
        private const val TAG = "MainActivityLogger"
        private const val PERMISSION_REQUEST_CODE = 101
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        ).filterNotNull().toTypedArray()

        // --- Broadcast Actions and Extras (match these in Service) ---
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

    // BroadcastReceiver to get updates from the service
    private val sensorUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SENSOR_UPDATE -> {
                    // Update sensor value TextViews
                    val ax = intent.getFloatExtra(EXTRA_ACCEL_X, 0f)
                    val ay = intent.getFloatExtra(EXTRA_ACCEL_Y, 0f)
                    val az = intent.getFloatExtra(EXTRA_ACCEL_Z, 0f)
                    val gx = intent.getFloatExtra(EXTRA_GYRO_X, 0f)
                    val gy = intent.getFloatExtra(EXTRA_GYRO_Y, 0f)
                    val gz = intent.getFloatExtra(EXTRA_GYRO_Z, 0f)

                    // Format to 2 decimal places
                    textViewAccelX.text = String.format(Locale.US, "%.2f", ax)
                    textViewAccelY.text = String.format(Locale.US, "%.2f", ay)
                    textViewAccelZ.text = String.format(Locale.US, "%.2f", az)
                    textViewGyroX.text = String.format(Locale.US, "%.2f", gx)
                    textViewGyroY.text = String.format(Locale.US, "%.2f", gy)
                    textViewGyroZ.text = String.format(Locale.US, "%.2f", gz)
                }
                ACTION_RECORDING_STOPPED -> {
                    // Show save confirmation
                    val filename = intent.getStringExtra(EXTRA_FILENAME)
                    if (filename != null) {
                        textViewSaveConfirmation.text = "Saved to $filename"
                        textViewSaveConfirmation.visibility = View.VISIBLE
                    } else {
                        textViewSaveConfirmation.text = "Recording stopped (file info unavailable)"
                        textViewSaveConfirmation.visibility = View.VISIBLE
                    }
                    // Update UI to reflect stopped state fully
                    isServiceRunning = false // Ensure state is consistent
                    updateUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)
        textViewStatus = findViewById(R.id.textViewStatus)
        // Get references for the new table TextViews
        textViewAccelX = findViewById(R.id.textViewAccelX)
        textViewAccelY = findViewById(R.id.textViewAccelY)
        textViewAccelZ = findViewById(R.id.textViewAccelZ)
        textViewGyroX = findViewById(R.id.textViewGyroX)
        textViewGyroY = findViewById(R.id.textViewGyroY)
        textViewGyroZ = findViewById(R.id.textViewGyroZ)
        textViewSaveConfirmation = findViewById(R.id.textViewSaveConfirmation)


        buttonStart.setOnClickListener {
            Log.d(TAG, "Start Button Clicked!")
            if (checkAndRequestPermissions()) {
                Log.d(TAG, "Permissions granted, prompting.")
                promptForSessionInfo()
            } else {
                Log.d(TAG, "Requesting permissions...")
            }
        }

        buttonStop.setOnClickListener {
            // Confirmation text is now handled by the broadcast receiver
            stopCollectionService()
        }

        updateUI() // Initial UI setup
        registerSensorUpdateReceiver() // Register receiver
    }

    override fun onResume() {
        super.onResume()
        // Refresh UI state potentially based on service status (might need more robust check than static var)
        // For now, rely on the static variable and broadcasts
        updateUI()
        registerSensorUpdateReceiver() // Re-register if paused
    }

    override fun onPause() {
        super.onPause()
        unregisterSensorUpdateReceiver() // Unregister to avoid leaks
    }

    override fun onDestroy() {
        unregisterSensorUpdateReceiver() // Ensure unregister on destroy
        super.onDestroy()
    }

    private fun registerSensorUpdateReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_SENSOR_UPDATE)
            addAction(ACTION_RECORDING_STOPPED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(sensorUpdateReceiver, intentFilter)
        Log.d(TAG, "SensorUpdateReceiver registered")
    }

    private fun unregisterSensorUpdateReceiver() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorUpdateReceiver)
            Log.d(TAG, "SensorUpdateReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver already unregistered or never registered.")
        }
    }


    private fun checkAndRequestPermissions(): Boolean {
        // ... (permission logic remains the same)
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "----> Requesting permissions: ${permissionsToRequest.joinToString()} <----")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
            false
        } else {
            Log.i(TAG, "All required permissions already granted.")
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // ... (permission result handling remains the same)
        Log.d(TAG, "onRequestPermissionsResult called. RequestCode: $requestCode")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            Log.d(TAG, "GrantResults: ${grantResults.joinToString()}")

            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i(TAG, "All permissions GRANTED by user.")
                Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Proceeding to promptForSessionInfo...")
                // Don't call prompt directly if already running? Check state.
                if (!isServiceRunning) {
                    promptForSessionInfo()
                } else {
                    Log.d(TAG,"Permissions granted, but service seems to be running already.")
                }

            } else {
                Log.w(TAG, "Permissions DENIED or request interrupted.")
                Toast.makeText(this, "Permissions Denied. Required for sensor collection.", Toast.LENGTH_LONG).show()
                updateUI()
            }
        } else {
            Log.w(TAG, "Received permission result for unknown requestCode: $requestCode")
        }
    }

    private fun promptForSessionInfo() {
        // ... (dialog logic remains the same)
        if (isServiceRunning) {
            Toast.makeText(this, "Recording is already in progress.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_session_info, null)
        val editTextActivity = dialogView.findViewById<EditText>(R.id.editTextActivity)
        val editTextSubject = dialogView.findViewById<EditText>(R.id.editTextSubject)

        AlertDialog.Builder(this)
            .setTitle("New Session Details")
            .setView(dialogView)
            .setPositiveButton("Start") { dialog, _ ->
                val activity = editTextActivity.text.toString().trim()
                val subject = editTextSubject.text.toString().trim()

                if (activity.isNotEmpty() && subject.isNotEmpty()) {
                    startCollectionService(activity, subject)
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Activity and Subject cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    // --- Service Control ---

    private fun startCollectionService(activity: String, subject: String) {
        Log.d(TAG, "Requesting service start")
        // Clear previous save message
        textViewSaveConfirmation.visibility = View.GONE
        textViewSaveConfirmation.text = ""

        val serviceIntent = Intent(this, SensorCollectionService::class.java).apply {
            action = SensorCollectionService.ACTION_START_SERVICE
            putExtra(SensorCollectionService.EXTRA_ACTIVITY_NAME, activity)
            putExtra(SensorCollectionService.EXTRA_SUBJECT_ID, subject)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true // Assume it starts successfully
        updateUI()
        // Sensor values will be updated via broadcast receiver
    }

    private fun stopCollectionService() {
        Log.d(TAG, "Requesting service stop")
        if (!isServiceRunning) {
            Log.w(TAG, "Stop requested, but service not marked as running.")
            // Still send stop intent just in case
        }
        val serviceIntent = Intent(this, SensorCollectionService::class.java).apply {
            action = SensorCollectionService.ACTION_STOP_SERVICE
        }
        stopService(serviceIntent)
        // Don't set isServiceRunning = false here. Let the confirmation broadcast handle it.
        // updateUI() will be called when the broadcast is received.
        // Set UI elements to a "Stopping..." state if desired
        textViewStatus.text = "Status: Stopping..."
        buttonStop.isEnabled = false // Disable stop button while stopping
    }

    // --- UI Update Logic ---
    private fun updateUI() {
        runOnUiThread {
            if (isServiceRunning) {
                textViewStatus.text = "Status: Recording..." // Simplified status
                buttonStart.isEnabled = false
                buttonStop.isEnabled = true
                // Clear confirmation text if we are starting/running
                if (textViewSaveConfirmation.visibility == View.VISIBLE) {
                    textViewSaveConfirmation.visibility = View.GONE
                    textViewSaveConfirmation.text = ""
                }
                // Sensor values are updated by the BroadcastReceiver, no need to set them here
            } else {
                textViewStatus.text = "Status: Stopped"
                buttonStart.isEnabled = true
                buttonStop.isEnabled = false // Controlled by isServiceRunning and broadcast
                // Set sensor values to N/A when stopped
                textViewAccelX.text = "N/A"
                textViewAccelY.text = "N/A"
                textViewAccelZ.text = "N/A"
                textViewGyroX.text = "N/A"
                textViewGyroY.text = "N/A"
                textViewGyroZ.text = "N/A"
                // Confirmation text visibility/content is handled by the broadcast receiver
            }
        }
    }
}