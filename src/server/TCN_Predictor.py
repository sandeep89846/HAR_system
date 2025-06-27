import os
import pandas as pd
import numpy as np
import time
import joblib # For loading scaler and label encoder
import tensorflow as tf # For loading TCN model
from collections import deque # For efficient buffering
from sklearn.preprocessing import StandardScaler # Need the class definition
from sklearn.preprocessing import LabelEncoder # Need the class definition

from flask import Flask, render_template, url_for # Import render_template
from flask_socketio import SocketIO, emit
from flask import request # Import request object to get session ID

# --- Basic Configuration ---

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = r"D:\Har_Classification\src\models" # Directory containing TCN model, scaler, encoder
TEMPLATES_DIR = os.path.join(SCRIPT_DIR, 'templates') # Path to templates folder

app = Flask(__name__,
            template_folder=TEMPLATES_DIR,
            static_folder=os.path.join(SCRIPT_DIR, 'static'), # Define static folder path
            static_url_path='/static') 

app.config['SECRET_KEY'] = 'your_secret_key_tcn_dashboard!'
socketio = SocketIO(app, cors_allowed_origins="*")

# --- TCN Model Configuration ---
MODEL_FILENAME = os.path.join(MODEL_DIR, 'tcn_ABCDE_har_model.keras')
SCALER_FILENAME = os.path.join(MODEL_DIR, 'tcn_ABCDE_scaler.joblib')
LABEL_ENCODER_FILENAME = os.path.join(MODEL_DIR, 'tcn_ABCDE_label_encoder.joblib')
activity_map = { # Used for logging
    "A": "Walking",
    "B": "Jogging",
    "C": "Using Stairs",
    "D": "Sitting",
    "E": "Standing",
}


SAMPLING_RATE_HZ = 20
WINDOW_SIZE_SAMPLES = 60
STRIDE_SAMPLES = 30
NUM_FEATURES = 6 # accel_x/y/z, gyro_x/y/z

# --- Feature Order & Mapping ---
MODEL_FEATURE_ORDER = ['x_accel', 'y_accel', 'z_accel', 'x_gyro', 'y_gyro', 'z_gyro']
INCOMING_TO_MODEL_MAP = {
    'accel_x': 'x_accel', 'accel_y': 'y_accel', 'accel_z': 'z_accel',
    'gyro_x': 'x_gyro', 'gyro_y': 'y_gyro', 'gyro_z': 'z_gyro'
}
EXTRACTION_ORDER = ['accel_x', 'accel_y', 'accel_z', 'gyro_x', 'gyro_y', 'gyro_z']

# --- Global Variables ---
tcn_model = None
scaler = None
label_encoder = None
model_loaded = False
scaler_loaded = False
encoder_loaded = False

# Store the buffer associated with a specific mobile client (session ID)
client_buffers = {}
# Keep track of samples received since last prediction for each mobile client
samples_since_last_pred = {}
# Store the latest prediction data for each mobile client (for dashboard)
latest_predictions = {}

# --- Load Model, Scaler, and Label Encoder ---
def load_model_scaler_encoder():
    """Load the trained TCN model, scaler, and label encoder."""
    global tcn_model, scaler, label_encoder, model_loaded, scaler_loaded, encoder_loaded
    print("\n--- Loading TCN Prediction Components ---")
    try:
        print(f"Attempting to load model from: {MODEL_FILENAME}")
        if not os.path.exists(MODEL_FILENAME): raise FileNotFoundError(f"Model file not found at {MODEL_FILENAME}")
        tcn_model = tf.keras.models.load_model(MODEL_FILENAME)
        model_loaded = True
        print(f"  TCN Model loaded successfully.")

        print(f"Attempting to load scaler from: {SCALER_FILENAME}")
        if not os.path.exists(SCALER_FILENAME): raise FileNotFoundError(f"Scaler file not found at {SCALER_FILENAME}")
        scaler = joblib.load(SCALER_FILENAME)
        scaler_loaded = True
        print(f"  Scaler loaded successfully.")
        if hasattr(scaler, 'n_features_in_'): print(f"   Scaler expects {scaler.n_features_in_} features.")
        if scaler.n_features_in_ != NUM_FEATURES: print(f"⚠️ WARNING: Scaler expected {scaler.n_features_in_} features, but NUM_FEATURES is set to {NUM_FEATURES}.")

        print(f"Attempting to load label encoder from: {LABEL_ENCODER_FILENAME}")
        if not os.path.exists(LABEL_ENCODER_FILENAME): raise FileNotFoundError(f"Label encoder file not found at {LABEL_ENCODER_FILENAME}")
        label_encoder = joblib.load(LABEL_ENCODER_FILENAME)
        encoder_loaded = True
        print(f"  Label Encoder loaded successfully.")
        print(f"   Classes known by encoder: {label_encoder.classes_}")
        print("-----------------------------------------\n")
        return True

    except Exception as e:
        print(f"❌ Error loading components: {e}")
        print("   Ensure model/scaler/encoder files exist and paths are correct.")
        # Don't exit, allow server to run but indicate failure on dashboard
        return False

# --- Helper Function to Send Dashboard Updates ---
def send_dashboard_update():
    """Sends the current status and predictions to all dashboard clients."""
    status = {
        'model_loaded': model_loaded,
        'scaler_loaded': scaler_loaded,
        'encoder_loaded': encoder_loaded,
    }
    config = {
        'window_size': WINDOW_SIZE_SAMPLES,
        'stride': STRIDE_SAMPLES,
        'sampling_rate': SAMPLING_RATE_HZ,
        'num_features': NUM_FEATURES,
        'classes': list(label_encoder.classes_) if encoder_loaded else [],
    }
    data = {
        'status': status,
        'config': config,
        'client_count': len(client_buffers),
        'latest_predictions': latest_predictions, # Send snapshot
    }
    socketio.emit('dashboard_update', data) # Emit to all connected web clients
    # print("DBG: Sent dashboard update") # Uncomment for debugging

# --- Flask Routes ---
@app.route('/')
def index():
    """Serve the main dashboard HTML page."""
    # Pass initial status info to the template (optional, as JS fetches it)
    return render_template('index.html',
                           model_ready=model_loaded and scaler_loaded and encoder_loaded)

# --- WebSocket Events ---

# --- Events for BOTH Mobile App and Web Dashboard ---
@socketio.on('connect')
def handle_connect():
    """Handle any new client connection (mobile or web)."""
    client_sid = request.sid
    # We differentiate mobile vs web by whether they send 'sensor_data'
    # or request 'get_initial_dashboard_state'
    print(f"🔌 Client connected: {client_sid}")
    # Immediately send update to all dashboards about potentially new count
    # (though we don't know yet if it's mobile or web)
    # A slight delay helps ensure the web client is ready to receive
    socketio.sleep(0.1)
    send_dashboard_update()


@socketio.on('disconnect')
def handle_disconnect():
    """Handle any client disconnection."""
    client_sid = request.sid
    print(f"🚫 Client disconnected: {client_sid}")
    # If the disconnected client was a mobile app, remove its data
    if client_sid in client_buffers:
        del client_buffers[client_sid]
        print(f"   Removed buffer for mobile client {client_sid[-6:]}")
    if client_sid in samples_since_last_pred:
        del samples_since_last_pred[client_sid]
    if client_sid in latest_predictions:
        del latest_predictions[client_sid]
        print(f"   Removed last prediction for mobile client {client_sid[-6:]}")

    # Send update to remaining dashboard clients
    send_dashboard_update()

# --- Events specifically for WEB DASHBOARD ---
@socketio.on('get_initial_dashboard_state')
def handle_get_initial_state():
    """Send the current full state to a newly connected dashboard client."""
    print(f"📊 Dashboard client requested initial state: {request.sid}")
    send_dashboard_update() # Sends current state to the requesting client (and others)


# --- Events specifically for MOBILE APP ---
@socketio.on('sensor_data')
def handle_sensor_data(data_point):
    """Handle incoming sensor data from MOBILE APP and perform prediction."""
    client_sid = request.sid

    # If this is the first time we see sensor data from this SID, it's a mobile client
    if client_sid not in client_buffers:
        print(f"📱 First sensor data from mobile client: {client_sid[-6:]}. Initializing buffer.")
        client_buffers[client_sid] = deque(maxlen=(WINDOW_SIZE_SAMPLES + STRIDE_SAMPLES * 5)) # Use deque, larger buffer
        samples_since_last_pred[client_sid] = 0
        latest_predictions[client_sid] = {} # Initialize empty prediction
        # Send dashboard update as a new *mobile* client is confirmed
        send_dashboard_update()


    # Check if models are ready
    if not all([model_loaded, scaler_loaded, encoder_loaded]):
        print(f"⏳ Models/Components not loaded. Ignoring sensor data from {client_sid[-6:]}...")
        # Optionally emit a 'waiting' status to the specific mobile client
        # socketio.emit('status', {'message': 'Server initializing...'}, room=client_sid)
        return

    try:
        # 1. Extract Sensor Values in the Correct Order
        if not all(key in data_point for key in EXTRACTION_ORDER):
            missing_keys = [key for key in EXTRACTION_ORDER if key not in data_point]
            print(f"❌ Invalid data from {client_sid[-6:]}: Missing keys {missing_keys}.")
            return

        sensor_values_ordered = [data_point[key] for key in EXTRACTION_ORDER]
        timestamp_ms = data_point.get('timestamp', time.time() * 1000)

        # Store sensor data only
        client_buffers[client_sid].append(sensor_values_ordered)
        samples_since_last_pred[client_sid] += 1

        current_buffer = client_buffers[client_sid]
        buffer_len = len(current_buffer)

        # --- Prediction Logic ---
        if buffer_len >= WINDOW_SIZE_SAMPLES and samples_since_last_pred[client_sid] >= STRIDE_SAMPLES:
            start_pred_time = time.time()

            # Get window data (deque slicing is efficient)
            window_data_list = list(current_buffer)[-WINDOW_SIZE_SAMPLES:]
            window_np = np.array(window_data_list, dtype=np.float32)

            if window_np.shape != (WINDOW_SIZE_SAMPLES, NUM_FEATURES):
                print(f"❌ Shape mismatch for {client_sid[-6:]}. Expected {(WINDOW_SIZE_SAMPLES, NUM_FEATURES)}, Got {window_np.shape}. Skipping.")
                samples_since_last_pred[client_sid] = 0 # Reset counter
                return

            # Scale data
            try:
                scaled_window = scaler.transform(window_np)
            except Exception as e:
                 print(f"❌ Scaling error for {client_sid[-6:]}: {e}")
                 samples_since_last_pred[client_sid] = 0 # Reset counter
                 return

            # Reshape for TCN
            scaled_window_reshaped = scaled_window.reshape(1, WINDOW_SIZE_SAMPLES, NUM_FEATURES)

            # Predict
            try:
                pred_proba = tcn_model.predict(scaled_window_reshaped, verbose=0)
                prediction_index = np.argmax(pred_proba, axis=1)[0]
                prediction_label = label_encoder.inverse_transform([prediction_index])[0]
                prediction_confidence = float(pred_proba[0][prediction_index]) # Ensure float

                end_pred_time = time.time()
                pred_duration = (end_pred_time - start_pred_time) * 1000

                # Log to server console
                print(f"SID {client_sid[-6:]}: Pred -> {activity_map.get(prediction_label, prediction_label)} ({prediction_confidence*100:.1f}%) | Dur: {pred_duration:.1f} ms")

                # Emit prediction to the SPECIFIC MOBILE client
                socketio.emit('prediction', {
                    'activity': prediction_label,
                    'confidence': prediction_confidence
                    }, room=client_sid)

                # Update the latest prediction for the dashboard
                latest_predictions[client_sid] = {
                     'activity': prediction_label,
                     'confidence': prediction_confidence,
                     'timestamp_ms': timestamp_ms # Store timestamp of prediction trigger
                }
                # Send update to ALL dashboard clients
                send_dashboard_update()

                # Reset sample counter
                samples_since_last_pred[client_sid] = 0

            except Exception as e:
                print(f"❌ Prediction/Label error for {client_sid[-6:]}: {e}")
                samples_since_last_pred[client_sid] = 0 # Reset counter
                # Clear latest prediction for this client on error? Optional.
                # latest_predictions[client_sid] = {'activity': 'Error', 'confidence': 0.0, 'timestamp_ms': timestamp_ms}
                # send_dashboard_update()


        # Deque handles buffer trimming automatically via maxlen

    except KeyError as e:
        print(f"❌ Invalid data structure from {client_sid[-6:]}: Missing key {e}.")
    except Exception as e:
        print(f"❌ Unexpected error for {client_sid[-6:]}: {type(e).__name__} - {e}")
        # Reset counters/buffers on severe errors?
        # if client_sid in samples_since_last_pred: samples_since_last_pred[client_sid] = 0
        # if client_sid in client_buffers: client_buffers[client_sid].clear()
        # if client_sid in latest_predictions: del latest_predictions[client_sid]
        # send_dashboard_update()


if __name__ == '__main__':
    print("\n🚀 Starting HAR TCN Real-time Predictor with Dashboard on 0.0.0.0:80")
    # Optional: Force CPU
    # os.environ['CUDA_VISIBLE_DEVICES'] = '-1'
    # tf.config.set_visible_devices([], 'GPU')

    if load_model_scaler_encoder():
         print("\n✅ Components loaded successfully.")
    else:
         print("\n⚠️ Running with failed component loading. Dashboard will show errors.")

    print("\n📡 Server ready. Open http://<your_server_ip>:80 in a browser for the dashboard.")
    print("url: http://localhost:80")
    print("   Connect your Android app. Waiting for sensor data...")
    # debug=False and use_reloader=False are important for stability with SocketIO/TF
    socketio.run(app, host='0.0.0.0', port=80, debug=False, use_reloader=False, allow_unsafe_werkzeug=True) 