// --- Global Variables & Configuration ---
let socket = null; // Initialize socket connection later
let clientHistories = {}; // Store { sid: [ { timestamp_ms, activity }, ... ] }
let selectedInsightDuration = 5 * 60 * 1000; // Default 5 minutes in milliseconds
const MAX_HISTORY_DURATION = 30 * 60 * 1000; // Max history to keep (30 mins)

// Map activities to CSS classes and display names (Keep consistent with Python & CSS)
const activityConfig = {
    "A": { name: "Walking", color: "#28a745", class: "activity-walking" },
    "B": { name: "Jogging", color: "#fd7e14", class: "activity-jogging" },
    "C": { name: "Using Stairs", color: "#ffc107", class: "activity-using-stairs" },
    "D": { name: "Sitting", color: "#17a2b8", class: "activity-sitting" },
    "E": { name: "Standing", color: "#6f42c1", class: "activity-standing" },
    "Unknown": { name: "Unknown", color: "#6c757d", class: "activity-unknown" },
    "Error": { name: "Error", color: "#dc3545", class: "activity-error" }
};

// --- DOM Element References ---
const serverStatusEl = document.getElementById('server-status');
const modelStatusEl = document.getElementById('model-status');
const scalerStatusEl = document.getElementById('scaler-status');
const encoderStatusEl = document.getElementById('encoder-status');
const clientCountEl = document.getElementById('client-count');
const clientsListEl = document.getElementById('clients-list');
const insightsAreaEl = document.getElementById('insights-area');
const timeButtons = document.querySelectorAll('.time-btn');

// Status Indicators
const serverIndicator = document.getElementById('server-indicator');
const modelIndicator = document.getElementById('model-indicator');
const scalerIndicator = document.getElementById('scaler-indicator');
const encoderIndicator = document.getElementById('encoder-indicator');
const clientCountIndicator = document.getElementById('client-count-indicator');

// Config Display
const configWindowEl = document.getElementById('config-window');
const configStrideEl = document.getElementById('config-stride');
const configRateEl = document.getElementById('config-rate');
const configFeaturesEl = document.getElementById('config-features');
const configClassesEl = document.getElementById('config-classes');

// --- Initialization ---
document.addEventListener('DOMContentLoaded', () => {
    connectWebSocket();
    setupEventListeners();
});

// --- WebSocket Connection ---
function connectWebSocket() {
    // Use io() which defaults to the host that serves the page
    socket = io({
        reconnectionAttempts: 5, // Try to reconnect a few times
        reconnectionDelay: 3000, // Wait 3 seconds between attempts
    });

    // --- Socket.IO Event Handlers ---
    socket.on('connect', () => {
        console.log('Connected to dashboard server!');
        updateServerStatus(true);
        // Request initial state when dashboard connects
        socket.emit('get_initial_dashboard_state');
    });

    socket.on('disconnect', (reason) => {
        console.warn('Disconnected from dashboard server.', reason);
        updateServerStatus(false);
        // Optionally clear the display or show a persistent disconnected state
        clearDashboard();
        clientsListEl.innerHTML = '<div class="no-clients">Server disconnected. Attempting to reconnect...</div>';
    });

    socket.on('connect_error', (err) => {
        console.error('Dashboard connection error:', err);
        updateServerStatus(false, 'Error');
        serverStatusEl.textContent = `Connection Error`;
    });

    // Main event to receive updates from the server
    socket.on('dashboard_update', (data) => {
        console.log('Received dashboard update:', data);
        updateStatusDisplay(data.status);
        updateConfigDisplay(data.config)
        updateClientCount(data.client_count);
        processLatestPredictions(data.latest_predictions); // Process updates
        updateInsightsDisplay(); // Refresh insights based on new data and current selection
    });
}
