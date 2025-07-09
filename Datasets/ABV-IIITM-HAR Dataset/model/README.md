# Human Activity Recognition (HAR) Project

This project aims to recognize human activities using sensor data (accelerometer and gyroscope) collected from multiple participants. The workflow includes data preprocessing, feature extraction, filtering, and training various machine learning models to classify activities.

---

## Table of Contents

- [Project Structure](#project-structure)
- [Data Description](#data-description)
- [Workflow Overview](#workflow-overview)
  - [1. Data Loading](#1-data-loading)
  - [2. Data Preprocessing](#2-data-preprocessing)
  - [3. Kalman Filtering](#3-kalman-filtering)
  - [4. Feature Extraction](#4-feature-extraction)
  - [5. Dimensionality Reduction (PCA)](#5-dimensionality-reduction-pca)
  - [6. Model Training & Evaluation](#6-model-training--evaluation)
- [Requirements](#requirements)
- [How to Run](#how-to-run)
- [Results](#results)
- [Acknowledgements](#acknowledgements)

---

## Project Structure

```
HAR/
│
├── data/
│   ├── 01/
│   ├── 02/
│   ├── ... (up to 11/)
│   │   └── [activity-session-folders]/
│   │       ├── Accelerometer.csv
│   │       ├── Gyroscope.csv
│   │       └── Annotation.csv
│
├── humanActivityRecognition.ipynb
└── README.md
```

---

## Data Description

- **Location:** `data/`
- **Participants:** Folders `01/` to `11/` (each representing a participant).
- **Sessions:** Each participant folder contains subfolders for different activity sessions (e.g., walking, running, cycling).
- **Files per session:**
  - `Accelerometer.csv`: Raw accelerometer data (x, y, z axes).
  - `Gyroscope.csv`: Raw gyroscope data (x, y, z axes).
  - `Annotation.csv`: Activity labels and timing.

**Activities:**
- walk (`wlk`)
- idle (`idl`)
- stairs_up (`ups`)
- stairs_down (`dws`)
- running (`jog`)
- cycling (`cyc`)

---

## Workflow Overview

### 1. Data Loading

- The notebook loads all sensor data for each participant and session.
- Data is organized into a DataFrame, with each row representing a participant-activity recording.

### 2. Data Preprocessing

- Sensor readings are extracted as numpy arrays.
- Resultant acceleration and gyroscope signals are computed.
- Duration of each activity session is calculated.

### 3. Kalman Filtering

- A 1D Kalman filter is applied to all sensor axes to reduce noise.
- Both filtered and unfiltered datasets are retained for comparison.

### 4. Feature Extraction

- Features are extracted from both filtered and unfiltered data using a sliding window approach (window size: 128, overlap: 64).
- Extracted features include:
  - Mean, min-max, standard deviation, RMS, and number of peaks (APF) for each axis.
  - Correlations between axes and between accelerometer and gyroscope.
  - Variance of APF across axes.

### 5. Dimensionality Reduction (PCA)

- Principal Component Analysis (PCA) is used to visualize and reduce feature dimensionality.
- Helps in understanding feature separability for different activities.

### 6. Model Training & Evaluation

- Multiple classifiers are trained and evaluated:
  - Logistic Regression
  - Random Forest
  - Support Vector Machine (SVM)
  - K-Nearest Neighbors (KNN)
  - XGBoost
- 5-fold cross-validation is used for robust evaluation.
- Performance is compared between filtered and unfiltered data.

---

## Requirements

- Python 3.7+
- Jupyter Notebook
- numpy
- pandas
- matplotlib
- seaborn
- scikit-learn
- xgboost
- tensorflow
- tqdm
- scipy

Install requirements with:
```bash
pip install numpy pandas matplotlib seaborn scikit-learn xgboost tensorflow tqdm scipy
```

---

## How to Run

1. **Prepare Data:**
   - Place all participant folders (`01/`, `02/`, ..., `11/`) inside the `data/` directory.
   - Ensure each session folder contains the required CSV files.

2. **Open Notebook:**
   - Launch Jupyter Notebook or JupyterLab.
   - Open `humanActivityRecognition.ipynb`.

3. **Run All Cells:**
   - Execute all cells in order.
   - The notebook will:
     - Load and preprocess data
     - Apply Kalman filtering
     - Extract features
     - Train and evaluate models
     - Visualize results

---

## Results

- The notebook provides:
  - Data distribution visualizations
  - Filtered vs. unfiltered signal plots
  - Feature importance and PCA plots
  - Classification reports and confusion matrices for each model
  - Accuracy comparison between filtered and unfiltered data

**Example Results:**
- Random Forest (Filtered): ~91% accuracy
- Logistic Regression (Filtered): ~81% accuracy

---

## Acknowledgements

- Sensor data collected by project participants.
- Open-source libraries: scikit-learn, xgboost, tensorflow, pandas, numpy, matplotlib, seaborn, tqdm, scipy.

---

If you have any questions or need further help, please open an issue or contact the project maintainer. 