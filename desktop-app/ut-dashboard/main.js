const { app, BrowserWindow, ipcMain, Menu, screen } = require('electron');
const fs = require('fs');
const path = require('path');
const os = require('os');

const MACHINES = ['M1', 'M2', 'M3'];
const DATA_ROOT = path.join(os.homedir(), 'ut_data', 'hub', 'messages');
const REFRESH_INTERVAL_MS = 60_000;

let mainWindow;

function createWindow() {
  Menu.setApplicationMenu(null);

  const { x: areaX, y: areaY, width: areaWidth } = screen.getPrimaryDisplay().workArea;
  const winWidth = 510;
  const winHeight = 270;

  mainWindow = new BrowserWindow({
    width: winWidth,
    height: winHeight,
    x: areaX + areaWidth - winWidth - 20,
    y: areaY + 20,
    alwaysOnTop: true,
    visibleOnAllWorkspaces: true,
    resizable: false,
    useContentSize: true,
    frame: false,
    transparent: true,
    hasShadow: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  mainWindow.loadFile('index.html');
}

function readData() {
  const result = {};
  for (const machine of MACHINES) {
    const filePath = path.join(DATA_ROOT, machine, 'messages.json');
    try {
      const raw = fs.readFileSync(filePath, 'utf-8');
      result[machine] = { data: JSON.parse(raw) };
    } catch (err) {
      result[machine] = { error: err.message };
    }
  }
  return result;
}

function pushData() {
  if (mainWindow) {
    mainWindow.webContents.send('data-update', readData());
  }
}

app.whenReady().then(() => {
  ipcMain.handle('get-data', () => readData());

  createWindow();

  mainWindow.webContents.on('did-finish-load', () => {
    pushData();
    mainWindow.webContents.executeJavaScript(
      'JSON.stringify([document.body.scrollWidth, document.body.scrollHeight])'
    ).then((json) => {
      const [w, h] = JSON.parse(json);
      mainWindow.setContentSize(w, h);
    });
  });

  setInterval(pushData, REFRESH_INTERVAL_MS);
});

app.on('window-all-closed', () => app.quit());
