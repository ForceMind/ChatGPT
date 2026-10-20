import { contextBridge, ipcRenderer } from 'electron';
import type { Bridge, Command, Snapshot } from '../shared/model';
const bridge: Bridge = {
  snapshot: () => ipcRenderer.invoke('profiles:snapshot'),
  command: (command: Command) => ipcRenderer.invoke('profiles:command', command),
  subscribe: handler => { const listener = (_event: unknown, state: Snapshot) => handler(state); ipcRenderer.on('profiles:state', listener); return () => ipcRenderer.removeListener('profiles:state', listener); },
  onShortcut: handler => { const listener = (_event: unknown, key: string) => handler(key); ipcRenderer.on('profiles:shortcut', listener); return () => ipcRenderer.removeListener('profiles:shortcut', listener); }
};
contextBridge.exposeInMainWorld('profiles', bridge);
