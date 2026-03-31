const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    markAsDone: (id) => ipcRenderer.invoke('mark-as-done', id),
    postpone: (id, minutes) => ipcRenderer.invoke('postpone', id, minutes),
    onUpdateReminders: (callback) => ipcRenderer.on('update-reminders', (_event, value) => callback(value)),
    refreshItems: () => ipcRenderer.send('refresh-items'),
    openExternal: (url) => ipcRenderer.send('open-url', url),
    resizeWindow: (height) => ipcRenderer.send('resize-window', height),
    setIgnoreMouseEvents: (ignore, options) => ipcRenderer.send('set-ignore-mouse-events', ignore, options),
    onPollError: (callback) => ipcRenderer.on('poll-error', (_event, msg) => callback(msg))
});
