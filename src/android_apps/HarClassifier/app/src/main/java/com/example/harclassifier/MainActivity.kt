package com.example.harclassifier // Adjust package name

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels // Import by viewModels()
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.harclassifier.R

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels() // Get ViewModel instance

    // UI Elements
    private lateinit var buttonConnect: Button
    private lateinit var buttonDisconnect: Button
    private lateinit var textViewStatus: TextView
    private lateinit var editTextServerUrl: EditText
    private lateinit var checkBoxIsLocal: CheckBox
    private lateinit var textViewPrediction: TextView
    private lateinit var textViewAccelData: TextView
    private lateinit var textViewGyroData: TextView

    private lateinit var broadcaster: LocalBroadcastManager

    // --- Broadcast Receiver ---
    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SensorService.BROADCAST_STATUS_UPDATE -> {
                    val statusStr = intent.getStringExtra(SensorService.EXTRA_STATUS)
                    val message = intent.getStringExtra("message") // Optional message
                    if (intent.hasExtra("is_running")) {
                        viewModel.setServiceRunning(intent.getBooleanExtra("is_running", false))
                    }

                    statusStr?.let {
                        val status = ConnectionStatus.entries.find { e -> e.name == it } ?: ConnectionStatus.ERROR
                        viewModel.updateStatus(status)
                        if (status == ConnectionStatus.ERROR && message != null) {
                            Toast.makeText(applicationContext, "Error: $message", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                SensorService.BROADCAST_PREDICTION_UPDATE -> {
                    val prediction = intent.getStringExtra(SensorService.EXTRA_PREDICTION) ?: "---"
                    viewModel.updatePrediction(prediction)
                }
                SensorService.BROADCAST_SENSOR_UPDATE -> {
                    val accel = intent.getStringExtra(SensorService.EXTRA_ACCEL_STR)
                    val gyro = intent.getStringExtra(SensorService.EXTRA_GYRO_STR)
                    viewModel.updateSensorDisplay(
                        accel ?: viewModel.lastAccelData.value ?: "0.0, 0.0, 0.0",
                        gyro ?: viewModel.lastGyroData.value ?: "0.0, 0.0, 0.0"
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 101
        // Define required runtime permissions
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            // Add notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        ).filterNotNull().toTypedArray()

        // Helper for Service to update UI (use cautiously) - No longer strictly needed with broadcasts
        // private var instance: MainActivity? = null
        // fun getInstance(): MainActivity? = instance
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // instance = this // No longer strictly needed
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate")

        // Initialize UI
        buttonConnect = findViewById(R.id.buttonConnect)
        buttonDisconnect = findViewById(R.id.buttonDisconnect)
        textViewStatus = findViewById(R.id.textViewStatus)
        editTextServerUrl = findViewById(R.id.editTextServerUrl)
        checkBoxIsLocal = findViewById(R.id.checkBoxIsLocal)
        textViewPrediction = findViewById(R.id.textViewPrediction)
        textViewAccelData = findViewById(R.id.textViewAccelData)
        textViewGyroData = findViewById(R.id.textViewGyroData)

        broadcaster = LocalBroadcastManager.getInstance(this)

        // Observe ViewModel LiveData
        setupObservers()

        // Setup Button Listeners
        buttonConnect.setOnClickListener {
            Log.d(TAG, "Connect Button Clicked")
            if (checkAndRequestPermissions()) {
                startSensorService()
            } else {
                Log.w(TAG,"Permissions not granted yet.")
                // Request should have been triggered by checkAndRequestPermissions
            }
        }

        buttonDisconnect.setOnClickListener {
            Log.d(TAG, "Disconnect Button Clicked")
            stopSensorService()
        }

        // Initial UI state based on potential existing service state (optional)
        // Consider checking if service is already running using a static flag or ActivityManager (less reliable)
        // For simplicity, assume disconnected initially unless updated by broadcast
        updateUiComponents(viewModel.isServiceRunning.value ?: false, viewModel.connectionStatus.value ?: ConnectionStatus.DISCONNECTED)

    }

    override fun onStart() {
        super.onStart()
        // Register receiver
        val filter = IntentFilter().apply {
            addAction(SensorService.BROADCAST_STATUS_UPDATE)
            addAction(SensorService.BROADCAST_PREDICTION_UPDATE)
            addAction(SensorService.BROADCAST_SENSOR_UPDATE)
        }
        broadcaster.registerReceiver(messageReceiver, filter)
        // Query service status maybe? Less reliable. Broadcasts are better.
        Log.d(TAG, "Broadcast receiver registered")
    }

    override fun onStop() {
        super.onStop()
        // Unregister receiver
        broadcaster.unregisterReceiver(messageReceiver)
        Log.d(TAG, "Broadcast receiver unregistered")
    }

    /* // No longer strictly needed with broadcasts
     override fun onDestroy() {
         super.onDestroy()
         instance = null
     }
     */


    private fun setupObservers() {
        viewModel.connectionStatus.observe(this, Observer { status ->
            textViewStatus.text = "Status: ${status.name}"
            updateUiComponents(viewModel.isServiceRunning.value ?: false, status) // Update buttons based on status
        })

        viewModel.lastPrediction.observe(this, Observer { prediction ->
            textViewPrediction.text = prediction
        })

        viewModel.lastAccelData.observe(this, Observer { data ->
            textViewAccelData.text = data
        })

        viewModel.lastGyroData.observe(this, Observer { data ->
            textViewGyroData.text = data
        })

        viewModel.isServiceRunning.observe(this, Observer { isRunning ->
            Log.d(TAG, "Service running state changed: $isRunning")
            updateUiComponents(isRunning, viewModel.connectionStatus.value ?: ConnectionStatus.DISCONNECTED)
        })
    }

    private fun updateUiComponents(isRunning: Boolean, status: ConnectionStatus) {
        runOnUiThread { // Ensure UI updates are on main thread
            buttonConnect.isEnabled = !isRunning // Can connect only if not running
            buttonDisconnect.isEnabled = isRunning // Can disconnect only if running

            // Optionally refine status text
            val statusText = when {
                isRunning && status == ConnectionStatus.CONNECTED -> "Status: Connected & Recording"
                isRunning && status == ConnectionStatus.CONNECTING -> "Status: Connecting..."
                isRunning && status == ConnectionStatus.ERROR -> "Status: Error (Service Active)"
                isRunning -> "Status: Service Active (${status.name})" // Fallback if running but not connected/error
                else -> "Status: Disconnected" // Not running
            }
            textViewStatus.text = statusText
        }
    }


    // --- Permission Handling (Corrected) ---
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG,"Requesting permissions: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
            false // Permissions are being requested
        } else {
            Log.i(TAG,"All required permissions already granted.")
            true // Permissions already granted
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG,"onRequestPermissionsResult called. RequestCode: $requestCode")

        if (requestCode == PERMISSION_REQUEST_CODE) {
            Log.d(TAG,"GrantResults: ${grantResults.joinToString()}")
            // Check if ALL requested permissions were granted
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i(TAG,"All permissions GRANTED by user.")
                Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
                // Permissions are granted, now try starting the service
                startSensorService()
            } else {
                Log.w(TAG,"Permissions DENIED or request interrupted.")
                Toast.makeText(this, "Permissions Denied. Required for sensor collection.", Toast.LENGTH_LONG).show()
                // Update UI to reflect denial - button remains enabled to allow retry
                // updateUiComponents(false, ConnectionStatus.DISCONNECTED) // No need to explicitly call here
            }
        } else {
            Log.w(TAG, "Received permission result for unknown requestCode: $requestCode")
        }
    }


    // --- Service Control ---
    private fun startSensorService() {
        val url = editTextServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
            return
        }
        // Basic validation (can be improved)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "URL should start with http:// or https://", Toast.LENGTH_SHORT).show()
            return
        }


        Log.i(TAG, "Attempting to start SensorService with URL: $url")
        viewModel.updateStatus(ConnectionStatus.CONNECTING) // Update UI immediately

        val serviceIntent = Intent(this, SensorService::class.java).apply {
            action = SensorService.ACTION_START_SERVICE
            putExtra(SensorService.EXTRA_SERVER_URL, url)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        // Note: isServiceRunning state will be updated via broadcast from the service itself
    }

    private fun stopSensorService() {
        Log.i(TAG, "Attempting to stop SensorService")
        // viewModel.updateStatus(ConnectionStatus.DISCONNECTED) // Status updated by service broadcast
        val serviceIntent = Intent(this, SensorService::class.java).apply {
            action = SensorService.ACTION_STOP_SERVICE
        }
        stopService(serviceIntent)
        // Note: isServiceRunning state will be updated via broadcast from the service itself
    }
}