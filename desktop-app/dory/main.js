const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

const SERVER_URL = 'http://localhost:3100';
const OWNER = 'toddy';

let criticalWindow = null;
let highWindow = null;
let standardWindow = null;

function effectiveTime(reminder) {
    const next = new Date(reminder.nextOccurrence);
    if (reminder.snoozeUntil) {
        const snooze = new Date(reminder.snoozeUntil);
        if (snooze > next) return snooze;
    }
    return next;
}

function getMinutesTo(reminder) {
    return (effectiveTime(reminder).getTime() - Date.now()) / 60000;
}

function createCriticalWindow() {
    if (criticalWindow) return;
    const { screen } = require('electron');
    const { width, height } = screen.getPrimaryDisplay().workAreaSize;
    const margin = 20;

    criticalWindow = new BrowserWindow({
        width: 825,
        height: height - (margin * 2),
        x: margin,
        y: margin,
        transparent: true, frame: false, alwaysOnTop: true, resizable: false,
        movable: false, skipTaskbar: true, show: false,
        webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true }
    });
    criticalWindow.loadFile('ui/critical.html');
}

function createHighWindow() {
    if (highWindow) return;
    const { screen } = require('electron');
    const { width, height } = screen.getPrimaryDisplay().workAreaSize;
    const margin = 20;

    highWindow = new BrowserWindow({
        width: 400,
        height: 350,
        x: margin,
        y: height - 350 - margin,
        transparent: true, frame: false, alwaysOnTop: true, resizable: false,
        movable: false, skipTaskbar: true, show: false,
        webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true }
    });
    highWindow.loadFile('ui/high.html');
}

function createStandardWindow() {
    if (standardWindow) return;
    const { screen } = require('electron');
    const { width, height } = screen.getPrimaryDisplay().workAreaSize;
    const margin = 20;

    standardWindow = new BrowserWindow({
        width: 200,
        height: 200,
        x: margin,
        y: height - 200 - margin,
        transparent: true, frame: false, alwaysOnTop: true, resizable: false,
        movable: false, skipTaskbar: true, show: false, roundedCorners: false,
        webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true }
    });
    standardWindow.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
    standardWindow.loadFile('ui/standard.html');
}

async function checkReminders() {
    try {
        const response = await fetch(
            `${SERVER_URL}/api/v1/dory/reminders?owner=${OWNER}&status=LIVE`
        );
        const reminders = await response.json();

        const sortedItems = reminders.sort((a, b) =>
            effectiveTime(a) - effectiveTime(b)
        );

        const leader = sortedItems[0];
        const minsToLeader = leader ? getMinutesTo(leader) : Infinity;

        if (minsToLeader <= 0) {
            if (!criticalWindow) createCriticalWindow();
            if (!criticalWindow.isVisible()) {
                criticalWindow.showInactive();
                if (highWindow) highWindow.hide();
                if (standardWindow) standardWindow.hide();
            }
            criticalWindow.webContents.send('update-reminders', leader);
        } else if (minsToLeader <= 10) {
            if (!highWindow) createHighWindow();
            if (!highWindow.isVisible()) {
                highWindow.showInactive();
                if (criticalWindow) criticalWindow.hide();
                if (standardWindow) standardWindow.hide();
            }
            highWindow.webContents.send('update-reminders', {
                current: leader,
                next: sortedItems[1] || null
            });
        } else if (leader) {
            if (!standardWindow) createStandardWindow();
            if (!standardWindow.isVisible()) {
                standardWindow.showInactive();
                if (criticalWindow) criticalWindow.hide();
                if (highWindow) highWindow.hide();
            }
            standardWindow.webContents.send('update-reminders', sortedItems);
        } else {
            if (!standardWindow) createStandardWindow();
            if (!standardWindow.isVisible()) {
                standardWindow.showInactive();
                if (criticalWindow) criticalWindow.hide();
                if (highWindow) highWindow.hide();
            }
            standardWindow.webContents.send('update-reminders', []);
        }
    } catch (err) {
        console.error('Poll failed:', err.message);
        if (!standardWindow) createStandardWindow();
        if (!standardWindow.isVisible()) standardWindow.showInactive();
        standardWindow.webContents.send('poll-error', err.message);
    }
}

// Align polling to the :00-second mark of each clock minute,
// then fire every 60s to stay on that rhythm.
function schedulePolling() {
    const now = new Date();
    const secsUntilZero = (60 - now.getSeconds()) % 60 || 60;
    setTimeout(() => {
        checkReminders();
        setInterval(checkReminders, 60000);
    }, secsUntilZero * 1000);
}

app.whenReady().then(() => {
    if (app.dock) {
        app.dock.setIcon(path.join(__dirname, 'assets/icon.png'));
    }
    checkReminders();
    schedulePolling();

    ipcMain.handle('mark-as-done', async (e, id) => {
        try {
            const res = await fetch(
                `${SERVER_URL}/api/v1/dory/reminders/${id}/done`,
                { method: 'PATCH' }
            );
            if (!res.ok) return false;
            checkReminders();
            return true;
        } catch (e) { console.error(e); return false; }
    });

    ipcMain.handle('postpone', async (e, id, mins) => {
        try {
            const res = await fetch(
                `${SERVER_URL}/api/v1/dory/reminders/${id}/snooze?minutes=${mins}`,
                { method: 'PATCH' }
            );
            if (!res.ok) return false;
            checkReminders();
            return true;
        } catch (e) { console.error(e); return false; }
    });

    ipcMain.on('resize-window', (_e, contentHeight) => {
        if (!standardWindow) return;
        const { screen } = require('electron');
        const { height } = screen.getPrimaryDisplay().workAreaSize;
        const margin = 20;
        const winHeight = Math.min(contentHeight, height - margin * 2);
        standardWindow.setBounds({ x: margin, y: height - winHeight - margin, width: 200, height: winHeight });
    });

    ipcMain.on('set-ignore-mouse-events', (e, ignore, options) => {
        const win = BrowserWindow.fromWebContents(e.sender);
        if (win) win.setIgnoreMouseEvents(ignore, options || {});
    });

    ipcMain.on('open-url', (e, url) => require('electron').shell.openExternal(url));
    ipcMain.on('refresh-items', () => checkReminders());
});
