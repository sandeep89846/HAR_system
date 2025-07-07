# Human Activity Recognition using Temporal Convolutional Network

A real-time Human Activity Recognition (HAR) system that uses smartphone inertial sensors (accelerometer and gyroscope) to classify five different activities: Walking, Jogging, Using Stairs, Sitting, and Standing.

## 🚀 Features

- **Real-Time Predictions**: Live activity classification on streaming sensor data
- **Custom Data Collection**: Built-in Android app for collecting labeled sensor data
- **Extensible Architecture**: Easy to add new activities by collecting additional data
- **Fast Inference**: Predictions in under 50ms per window (3-second windows)
- **High Accuracy**: 99.64% accuracy on test data, 91%+ on real-world validation

## 📱 System Architecture

The system consists of three main components:

1. **Sensor Logger App** (Android): Collects and labels sensor data
2. **TCN Model**: Temporal Convolutional Network for activity classification
3. **HAR Classifier App** + **Prediction Server**: Real-time classification system

## 🛠️ Installation

### Prerequisites

- Python 3.7+
- TensorFlow 2.x
- Android Studio (for mobile apps)
- Required Python packages:

```bash
pip install tensorflow numpy pandas scikit-learn flask flask-socketio joblib
```

### Model Setup

1. Clone the repository:

```bash
git clone <repository-url>
cd human-activity-recognition
```

2. Ensure the following trained model files are in the `server/models/` directory:
   - `tcn_ABCDE_har_model.keras`
   - `tcn_ABCDE_scaler.joblib`
   - `tcn_ABCDE_label_encoder.joblib`

### Android Apps Setup

1. Open the Android projects in `src/android_apps/` using Android Studio
2. Build and install both apps:
   - **SensorLogger**: For data collection
   - **HarClassifier**: For real-time predictions

## 🎯 Usage

### Data Collection

1. Open the **Sensor Logger** app
2. Enter Subject ID and select Activity type
3. Press "Start Recording" and perform the activity
4. Press "Stop Recording" to save data as CSV file

### Real-Time Classification

1. Start the prediction server:

```bash
cd server
python TCN_Predictor.py
```

2. Open the **HAR Classifier** app
3. Enter server IP address and port
4. Connect and start real-time activity recognition

### Training Your Own Model

1. Prepare your data using the provided notebook:

```bash
jupyter notebook src/notebook/TCNonOwnData.ipynb
```

2. Follow the preprocessing and training steps in the notebook

## 🫠 Customizing the TCN Model for Your Dataset

The training pipeline is built to be modular and configurable. If your dataset follows the expected format (see required columns in the script), you do **not** need to rewrite major parts of the code. Instead, focus on **tuning model parameters** to match the temporal characteristics of your data.

### 🔧 Where to Modify

Open the training script (`TCNonOwnData.ipynb` or `tcnonowndata.py`) and scroll to the section labeled:

```python
# --- Model Hyperparameters ---
```

These constants control how the Temporal Convolutional Network behaves.

### 📏 Key Concepts

For most time-series activity data, the default configuration works well. However, depending on your specific use case (e.g., shorter activities, higher/lower sampling rate), you may want to tune the **receptive field** of the model — the amount of temporal context it uses to make a prediction.

### 🧩 Parameters that Affect the Receptive Field:



- **Default**: `7`
- Larger values allow each convolution to cover more time steps.
- Increase if your signal patterns are smoother or slower-changing.

<br/>

- **Default**: `5`
- The number of residual blocks stacked together.
- More blocks = deeper temporal context.
- Reducing to `3–4` works better for fast, short-duration activities.

<br/>

- **Default**: `[2**i for i in range(NUM_TCN_BLOCKS)]` → `[1, 2, 4, 8, 16]`
- Exponentially increases the gap between filter taps — critical for modeling long-range dependencies.
- You can customize this to `[1, 2, 4]` (for short-term focus) or `[1, 2, 4, 8, 16, 32]` (for longer sequences).

### ✅ Example Configuration:

```python
KERNEL_SIZE = 9
NUM_TCN_BLOCKS = 6
DILATION_RATES = [1, 2, 4, 8, 16, 32]  # Receptive field grows significantly
```

No additional code changes are needed — simply adjust these parameters and re-run the script. The training pipeline will automatically handle:

- Windowing
- Splitting
- Label encoding
- Feature scaling
- Model training
- Evaluation and result saving

This makes it easy to experiment and fine-tune the model for your own sensor data.

## 📊 Model Performance

### Offline Test Results

- **Overall Accuracy**: 99.64%
- **Test Loss**: 0.0339

| Activity     | Precision | Recall | F1-Score | Support |
| ------------ | --------- | ------ | -------- | ------- |
| Walking      | 1.0000    | 0.9932 | 0.9966   | 4099    |
| Jogging      | 1.0000    | 1.0000 | 1.0000   | 2892    |
| Using Stairs | 0.9883    | 1.0000 | 0.9941   | 1010    |
| Sitting      | 0.9947    | 0.9953 | 0.9950   | 3010    |
| Standing     | 0.9927    | 0.9973 | 0.9950   | 3010    |

### Real-World Validation

- **Validation Run 1**: 91.68% accuracy on unseen subject
- **Validation Run 2**: 91.06% accuracy on unseen subject

## 🏗️ Technical Details

### Model Architecture

- **Model Type**: Temporal Convolutional Network (TCN)
- **Input**: 60 time steps (3 seconds) × 6 features (3-axis accel + 3-axis gyro)
- **Sampling Rate**: 20 Hz
- **Window Size**: 3 seconds with 75% overlap
- **Receptive Field**: 9.35 seconds

### Key Components

- **5 TCN Residual Blocks** with dilated convolutions
- **Dilation Rates**: [1, 2, 4, 8, 16]
- **Global Average Pooling** for temporal dimension reduction
- **Regularization**: Spatial Dropout, Standard Dropout, L2 regularization

### Data Sources

- **WISDM Dataset**: For Jogging, Sitting, Standing activities
- **Custom Collected Data**: For Walking and Using Stairs activities

## 📁 Project Structure

```
├── src/
│   ├── android_apps/
│   │   ├── HarClassifier/          # Real-time classification app
│   │   └── SensorLogger/           # Data collection app
│   ├── notebook/
│   │   └── TCNonOwnData.ipynb      # Training notebook
│   └── server/
│       ├── models/                 # Trained model files
│       ├── static/                 # Web assets
│       ├── templates/              # HTML templates
│       └── TCN_Predictor.py        # Prediction server
├── results/
│   ├── offline_evaluation/         # Test results
│   └── realtime_validation/        # Real-world validation
└── README.md
```

## 🔧 Configuration

### Server Configuration

Edit `TCN_Predictor.py` to modify:

- Server host/port
- Model file paths
- Buffer sizes
- Prediction parameters

### Data Collection Configuration

- **Sampling Rate**: 20 Hz (configurable in Android apps)
- **Activities**: A=Walking, B=Jogging, C=Using Stairs, D=Sitting, E=Standing

### Datasets used for training
- **WISDM Dataset**: Public dataset for Jogging, Sitting, Standing. This data was preprocessed and resampled at 20Hz again before training and made into a csv file titled [resampled_normalized_phone_data.csv](https://drive.google.com/file/d/1dinzrzwi-d32mmo2t-e9VFCZQTZcJJcL/view?usp=sharing).

- We have collected data ourselves for Walking and Using stairs and the data from various user was compiled in a csv file titled as
[combined_collected_data.csv](https://drive.google.com/file/d/1-0GJBWYzEoJe-EB1T0S6iE-2hH8xJ_Qo/view?usp=sharing).



## 📚 Research Background

This project addresses the challenge of differentiating similar activities (particularly Walking vs. Using Stairs) by combining public datasets with custom-collected data. The TCN architecture was chosen for its effectiveness in sequence modeling and ability to capture long-range temporal dependencies.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/new-activity`)
3. Commit your changes (`git commit -am 'Add new activity classification'`)
4. Push to the branch (`git push origin feature/new-activity`)
5. Create a Pull Request

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 👥 Authors

- Ajay Kumar Meena
- Gontu Sandeep Kumar
- Prabhat Kumar
- Vikash Kumar

**Institution**: Atal Bihari Vajpayee Indian Institute of Information Technology and Management Gwalior

## 📞 Support

For questions or issues, please open an issue on GitHub or contact the development team.

## 🙏 Acknowledgments

- WISDM team for providing the public dataset
- TensorFlow and scikit-learn communities
- Android development resources and documentation

---

