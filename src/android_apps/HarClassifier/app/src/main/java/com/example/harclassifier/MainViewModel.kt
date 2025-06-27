package com.example.harclassifier // Adjust package name

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

class MainViewModel : ViewModel() {

    private val _connectionStatus = MutableLiveData(ConnectionStatus.DISCONNECTED)
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    private val _lastPrediction = MutableLiveData("---")
    val lastPrediction: LiveData<String> = _lastPrediction

    private val _lastAccelData = MutableLiveData("0.0, 0.0, 0.0")
    val lastAccelData: LiveData<String> = _lastAccelData

    private val _lastGyroData = MutableLiveData("0.0, 0.0, 0.0")
    val lastGyroData: LiveData<String> = _lastGyroData

    // Service running state (can be updated via broadcasts)
    private val _isServiceRunning = MutableLiveData(false)
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning


    fun updateStatus(status: ConnectionStatus) {
        _connectionStatus.postValue(status) // Use postValue if called from non-UI thread
    }

    fun updatePrediction(prediction: String) {
        _lastPrediction.postValue(prediction)
    }

    fun updateSensorDisplay(accel: String, gyro: String) {
        _lastAccelData.postValue(accel)
        _lastGyroData.postValue(gyro)
    }

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.postValue(isRunning)
    }
}