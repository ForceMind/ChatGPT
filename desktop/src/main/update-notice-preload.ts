import{contextBridge,ipcRenderer}from'electron';
contextBridge.exposeInMainWorld('updateNotice',{cancel:()=>ipcRenderer.send('updates:cancel-countdown')});
