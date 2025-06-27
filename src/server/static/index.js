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

// --- Event Listeners ---
function setupEventListeners() {
    timeButtons.forEach(button => {
        button.addEventListener('click', () => {
            // Update active button style
            timeButtons.forEach(btn => btn.classList.remove('active'));
            button.classList.add('active');

            // Set selected duration and update insights
            selectedInsightDuration = parseInt(button.dataset.duration) * 60 * 1000;
            updateInsightsDisplay();
        });
    });
}

// --- Update Functions ---

function updateServerStatus(isConnected, statusText = null) {
    if (isConnected) {
        serverStatusEl.textContent = statusText || 'Connected';
        serverIndicator.className = 'status-indicator ok'; // Green
    } else {
        serverStatusEl.textContent = statusText || 'Disconnected';
        serverIndicator.className = 'status-indicator error'; // Red
    }
}


function updateStatusDisplay(status) {
    modelStatusEl.textContent = status.model_loaded ? 'Yes' : 'No';
    modelIndicator.className = status.model_loaded ? 'status-indicator ok' : 'status-indicator error';

    scalerStatusEl.textContent = status.scaler_loaded ? 'Yes' : 'No';
    scalerIndicator.className = status.scaler_loaded ? 'status-indicator ok' : 'status-indicator error';

    encoderStatusEl.textContent = status.encoder_loaded ? 'Yes' : 'No';
    encoderIndicator.className = status.encoder_loaded ? 'status-indicator ok' : 'status-indicator error';
}

function updateConfigDisplay(config) {
    configWindowEl.textContent = config.window_size || '--';
    configStrideEl.textContent = config.stride || '--';
    configRateEl.textContent = config.sampling_rate || '--';
    configFeaturesEl.textContent = config.num_features || '--';
    configClassesEl.textContent = config.classes ? config.classes.join(', ') : '--';
}


function updateClientCount(count) {
    clientCountEl.textContent = count;
     clientCountIndicator.className = count > 0 ? 'status-indicator count active' : 'status-indicator count'; // Add pulse animation if clients > 0
}

function clearDashboard() {
    updateStatusDisplay({ model_loaded: false, scaler_loaded: false, encoder_loaded: false });
    updateClientCount(0);
    clientsListEl.innerHTML = '<div class="no-clients">Server disconnected.</div>';
    insightsAreaEl.innerHTML = '<div class="no-clients">Server disconnected.</div>';
    clientHistories = {}; // Clear history on disconnect
}


// --- Processing and Displaying Client Data ---

function processLatestPredictions(latestPredictions) {
    const now = Date.now();
    const receivedSids = Object.keys(latestPredictions);
    const currentDisplayedSids = Object.keys(clientHistories);

    // 1. Update history for existing clients and add new clients
    receivedSids.forEach(sid => {
        const predData = latestPredictions[sid];
        if (predData && predData.activity) {
            if (!clientHistories[sid]) {
                clientHistories[sid] = []; // Initialize history for new client
                console.log(`New client history initialized: ${sid}`);
            }

            // Avoid adding duplicate timestamps if updates are very frequent
            const lastEntry = clientHistories[sid].length > 0 ? clientHistories[sid][clientHistories[sid].length - 1] : null;
            if (!lastEntry || lastEntry.timestamp_ms !== predData.timestamp_ms) {
                 clientHistories[sid].push({
                     timestamp_ms: predData.timestamp_ms,
                     activity: predData.activity
                 });
                // Prune old history for this client
                pruneClientHistory(sid, now);
            }
        }
    });

     // 2. Remove histories for clients that are no longer in the latest update (disconnected)
     currentDisplayedSids.forEach(sid => {
         if (!receivedSids.includes(sid)) {
             delete clientHistories[sid];
             console.log(`Removed client history for disconnected SID: ${sid}`);
         }
     });


    // 3. Update the Live Client Prediction List UI
    updateLiveClientsList(latestPredictions);
}

function pruneClientHistory(sid, currentTime) {
    if (!clientHistories[sid]) return;
    const cutoffTime = currentTime - MAX_HISTORY_DURATION;
    clientHistories[sid] = clientHistories[sid].filter(entry => entry.timestamp_ms >= cutoffTime);
}

function updateLiveClientsList(predictions) {
    clientsListEl.innerHTML = ''; // Clear previous list
    const sids = Object.keys(predictions);

    if (sids.length === 0) {
        clientsListEl.innerHTML = '<div class="no-clients">No mobile clients connected or sending data yet.</div>';
        return;
    }

    sids.sort().forEach(sid => {
        const predData = predictions[sid];
        const clientCard = document.createElement('div');
        clientCard.classList.add('client-card');
        clientCard.id = `live-client-${sid}`;

        const sidEl = document.createElement('div'); // Use div for better block layout
        sidEl.classList.add('client-sid');
        sidEl.textContent = `Client SID: ...${sid.slice(-6)}`;
        clientCard.appendChild(sidEl);

        if (predData && predData.activity) {
            const config = activityConfig[predData.activity] || activityConfig["Unknown"];
            const confidence = predData.confidence !== null ? (predData.confidence * 100).toFixed(1) : 'N/A';

            const predEl = document.createElement('div');
            predEl.classList.add('prediction');
            predEl.innerHTML = `
                <span class="prediction-label">Activity:</span>
                <span class="prediction-activity" style="background-color: ${config.color};">${config.name}</span><br>
                <span class="prediction-label">Confidence:</span>
                <span class="prediction-confidence">${confidence}%</span>
            `;
            clientCard.appendChild(predEl);

            if (predData.timestamp_ms) {
                const timeEl = document.createElement('div');
                timeEl.classList.add('last-update-time');
                timeEl.textContent = `Updated: ${new Date(predData.timestamp_ms).toLocaleTimeString()}`;
                clientCard.appendChild(timeEl);
            }
        } else {
            const waitingEl = document.createElement('div');
            waitingEl.textContent = 'Waiting for first prediction...';
            waitingEl.style.color = '#888';
            clientCard.appendChild(waitingEl);
        }
        clientsListEl.appendChild(clientCard);
    });
}


// --- Insights Display Logic ---

function updateInsightsDisplay() {
    insightsAreaEl.innerHTML = ''; // Clear previous insights
    const now = Date.now();
    const startTime = now - selectedInsightDuration;
    const clientSids = Object.keys(clientHistories);

    if (clientSids.length === 0) {
        insightsAreaEl.innerHTML = '<div class="no-clients">No client data available for insights.</div>';
        return;
    }

    clientSids.sort().forEach(sid => {
        const history = clientHistories[sid];
        if (!history || history.length === 0) return; // Skip if no history for this client

        // Filter history for the selected time range
        const relevantHistory = history.filter(entry => entry.timestamp_ms >= startTime);
        if (relevantHistory.length === 0) return; // Skip if no data in this range

        const clientContainer = document.createElement('div');
        clientContainer.classList.add('insight-client-container');
        clientContainer.id = `insight-client-${sid}`;

        const sidEl = document.createElement('div');
        sidEl.classList.add('client-sid');
        sidEl.textContent = `Client SID: ...${sid.slice(-6)}`;
        clientContainer.appendChild(sidEl);

        const timelineBar = document.createElement('div');
        timelineBar.classList.add('insight-timeline-bar');

        // Create activity blocks for the timeline
        for (let i = 0; i < relevantHistory.length; i++) {
            const entry = relevantHistory[i];
            const nextEntry = relevantHistory[i + 1];

            // Calculate start and end times for this block
            const blockStartTime = Math.max(entry.timestamp_ms, startTime); // Clip start to timeline beginning
            let blockEndTime;
             if (nextEntry && nextEntry.timestamp_ms < now) {
                 blockEndTime = nextEntry.timestamp_ms; // Ends when next activity starts
             } else {
                  blockEndTime = now; // Assume current activity continues until 'now'
             }
             blockEndTime = Math.min(blockEndTime, now); // Clip end to 'now'

            // Calculate position and width as percentage of the total duration
            const blockStartOffset = blockStartTime - startTime;
            const blockDuration = blockEndTime - blockStartTime;

            if (blockDuration <= 0) continue; // Skip zero-duration blocks

            const leftPercent = (blockStartOffset / selectedInsightDuration) * 100;
            const widthPercent = (blockDuration / selectedInsightDuration) * 100;

            const activityBlock = document.createElement('div');
            activityBlock.classList.add('insight-activity-block');

            const config = activityConfig[entry.activity] || activityConfig["Unknown"];
            activityBlock.style.backgroundColor = config.color;
            activityBlock.style.left = `${leftPercent}%`;
            activityBlock.style.width = `${widthPercent}%`;
            activityBlock.title = `${config.name} (${new Date(entry.timestamp_ms).toLocaleTimeString()})`; // Tooltip

            timelineBar.appendChild(activityBlock);
        }

        clientContainer.appendChild(timelineBar);
        insightsAreaEl.appendChild(clientContainer);
    });

     // If after checking all clients, none had relevant history, show the message again
     if (insightsAreaEl.innerHTML === '') {
         insightsAreaEl.innerHTML = '<div class="no-clients">No client activity recorded in the selected time range.</div>';
     }
}

