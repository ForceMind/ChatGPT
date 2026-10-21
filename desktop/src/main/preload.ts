import { contextBridge, ipcRenderer, webUtils } from 'electron';
import type { Bridge, Command, Snapshot } from '../shared/model';
const bridge: Bridge = {
  snapshot: () => ipcRenderer.invoke('profiles:snapshot'),
  command: (command: Command) => ipcRenderer.invoke('profiles:command', command),
  subscribe: handler => { const listener = (_event: unknown, state: Snapshot) => handler(state); ipcRenderer.on('profiles:state', listener); return () => ipcRenderer.removeListener('profiles:state', listener); },
  onShortcut: handler => { const listener = (_event: unknown, key: string) => handler(key); ipcRenderer.on('profiles:shortcut', listener); return () => ipcRenderer.removeListener('profiles:shortcut', listener); },
  diagnostics: {
    list:()=>ipcRenderer.invoke('login-links:list'),
    clear:()=>ipcRenderer.invoke('login-links:clear'),
    subscribe:handler=>{const listener=()=>handler();ipcRenderer.on('login-links:changed',listener);return()=>ipcRenderer.removeListener('login-links:changed',listener);}
  },
  workbench: {
    assets: profileId => ipcRenderer.invoke('assets:snapshot', profileId),
    act: (profileId, action) => ipcRenderer.invoke('assets:action', profileId, action),
    importDropped: (profileId, files) => {
      if (!Array.isArray(files) || files.length > 100) return Promise.reject(new Error('一次最多导入 100 个文件'));
      const paths = files.map(file => webUtils.getPathForFile(file));
      if (paths.some(file => !file)) return Promise.reject(new Error('请拖入本机实际文件'));
      return ipcRenderer.invoke('assets:import-dropped', profileId, paths);
    },
    preview: (profileId, id) => ipcRenderer.invoke('assets:preview', profileId, id),
    readText: (profileId, id) => ipcRenderer.invoke('assets:read-text', profileId, id),
    capabilities: () => ipcRenderer.invoke('capabilities:snapshot'),
    agent: id => ipcRenderer.invoke('capabilities:agent', id),
    favoriteCapability: (id, favorite) => ipcRenderer.invoke('capabilities:favorite', id, favorite),
    convert: (profileId,ids,options) => ipcRenderer.invoke('assets:convert',profileId,ids,options),
    startHandoff:request=>ipcRenderer.invoke('handoff:start',request),
    handoffs:profileId=>ipcRenderer.invoke('handoff:list',profileId),
    cancelHandoff:(profileId,id)=>ipcRenderer.invoke('handoff:cancel',profileId,id),
    openHandoff:(profileId,id)=>ipcRenderer.invoke('handoff:open',profileId,id),
    subscribeHandoffs:handler=>{const listener=()=>handler();ipcRenderer.on('handoff:changed',listener);return()=>ipcRenderer.removeListener('handoff:changed',listener);},
    compose: options => ipcRenderer.invoke('capabilities:compose', options),
    subscribe: handler => { const listener = (_event: unknown, profileId: string) => handler(profileId); ipcRenderer.on('assets:changed', listener); return () => ipcRenderer.removeListener('assets:changed', listener); }
  }
};
contextBridge.exposeInMainWorld('profiles', bridge);
