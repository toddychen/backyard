const { contextBridge, ipcRenderer } = require('electron');

// Expose a safe, limited API to the renderer
contextBridge.exposeInMainWorld('dashboard', {
  getData: () => ipcRenderer.invoke('get-data'),
  onDataUpdate: (callback) => {
    ipcRenderer.on('data-update', (_event, data) => callback(data));
  },
  setIgnoreMouseEvents: (ignore, options) => ipcRenderer.send('set-ignore-mouse-events', ignore, options),
});
