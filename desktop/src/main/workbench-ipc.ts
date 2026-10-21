import { dialog, ipcMain, shell, type BrowserWindow, type IpcMainInvokeEvent } from 'electron';
import { open, rename, rm } from 'node:fs/promises';
import { constants, createWriteStream, existsSync, lstatSync, realpathSync } from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import path from 'node:path';
import type { Profile, SavedState } from '../shared/model';
import type { AssetAction } from '../shared/workbench';
import { AssetStore } from './asset-store';
import { AssetRuntime, assetMime } from './asset-runtime';
import { AssetDownloads } from './asset-downloads';
import { CapabilityCatalog } from './capabilities';
import { AssetConversions } from './asset-conversions';

export type WorkbenchHost = {
  dataDirectory: string;
  window: () => BrowserWindow | undefined;
  trusted: (event: IpcMainInvokeEvent) => boolean;
  currentProfile: () => Profile;
  state: () => SavedState;
  store: () => AssetStore;
  runtime: () => AssetRuntime;
  catalog: () => CapabilityCatalog;
  downloads: (profileId: string) => AssetDownloads | undefined;
  changed: (profileId: string) => void;
  openChat: (profileId: string, url: string) => void;
  favoriteCapability: (id: string, favorite: boolean) => void;
  conversions: () => AssetConversions;
};
const record = (v: unknown): v is Record<string,unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

export function installWorkbenchIPC(host: WorkbenchHost): void {
  const access = (event: IpcMainInvokeEvent, profileId?: unknown) => {
    if (process.platform !== 'darwin' || !host.trusted(event)) throw new Error('拒绝非本地工作台请求');
    if (profileId !== undefined && profileId !== host.currentProfile().id) throw new Error('账号已切换，请在当前账号重新选择资产');
  };
  const notify = (profileId: string) => host.changed(profileId);
  const importFiles = async (profile: Profile, files: string[]) => {
    if (!files.length || files.length > 100 || files.some(f => typeof f !== 'string' || !path.isAbsolute(f))) throw new Error('一次请选择 1–100 个本机文件');
    const failures: string[] = [];
    for (const file of files) {
      if (!host.state().profiles.some(p => p.id === profile.id)) throw new Error('原账号空间已移除，已停止导入，现有文件保留。');
      try { await host.store().importFile(profile.id,file,{kind:'import',profileName:profile.name},undefined,assetMime(file)); }
      catch { failures.push(path.basename(file)); }
      notify(profile.id);
    }
    if (failures.length) throw new Error(`${failures.length} 个文件未能导入：${failures.slice(0,3).join('、')}。原文件未修改，请查看资产状态。`);
  };
  ipcMain.handle('assets:snapshot',(event,profileId:unknown) => { access(event,profileId); return host.runtime().items(profileId as string); });
  ipcMain.handle('assets:preview',async(event,profileId:unknown,id:unknown) => {
    access(event,profileId); if(typeof id!=='string')throw new Error('无效资产');
    const before=host.store().get(profileId as string,id).status;
    try{return await host.runtime().preview(profileId as string,id);}
    finally{if(host.store().get(profileId as string,id).status!==before)notify(profileId as string);}
  });
  ipcMain.handle('assets:read-text',async(event,profileId:unknown,id:unknown) => { access(event,profileId); if(typeof id!=='string')throw new Error('无效资产'); await host.store().verifyFile(profileId as string,id); return host.runtime().text(profileId as string,id); });
  ipcMain.handle('assets:import-dropped',async(event,profileId:unknown,files:unknown) => {
    access(event,profileId); if(!Array.isArray(files)||!files.every(f=>typeof f==='string'))throw new Error('请拖入本机实际文件');
    const profile=host.currentProfile(); await importFiles(profile,files);
  });
  ipcMain.handle('assets:action',async(event,profileId:unknown,value:unknown) => {
    access(event,profileId);
    if(!record(value)||typeof value.type!=='string')throw new Error('无效资产操作');
    const action=value as AssetAction;
    const id = 'id' in action && typeof action.id==='string' ? action.id : '';
    if(action.type==='open-folder'){const directory=path.join(host.dataDirectory,'assets');if(lstatSync(directory).isSymbolicLink())throw new Error('资产目录是链接，请先检查应用数据目录。');const error=await shell.openPath(directory);if(error)throw new Error(error);return;}
    const profile=host.currentProfile(), store=host.store();
    switch(action.type) {
      case 'import': {
        const win=host.window();if(!win)throw new Error('窗口已关闭');
        const result=await dialog.showOpenDialog(win,{title:'导入到 '+profile.name,properties:['openFile','multiSelections']});
        if(!result.canceled)await importFiles(profile,result.filePaths);break;
      }
      case 'rename': if(typeof action.name!=='string')throw new Error('文件名无效'); store.rename(profile.id,id,action.name);break;
      case 'tags': store.setTags(profile.id,id,action.tags);break;
      case 'favorite': if(typeof action.favorite!=='boolean')throw new Error('收藏状态无效');store.editMany(profile.id,action.ids,{favorite:action.favorite});break;
      case 'trash': if(typeof action.trashed!=='boolean')throw new Error('回收站状态无效');store.editMany(profile.id,action.ids,{trashed:action.trashed});host.runtime().revoke();break;
      case 'pause': case 'resume': case 'cancel': {
        store.get(profile.id,id);const tracker=host.downloads(profile.id);if(!tracker)throw new Error('下载任务已结束');tracker[action.type](id);break;
      }
      case 'conversion-cancel': host.conversions().cancel(profile.id,id);break;
      case 'reveal': shell.showItemInFolder(store.resolveFile(profile.id,id));break;
      case 'export': {
        const win=host.window();if(!win)throw new Error('窗口已关闭');
        const asset=store.get(profile.id,id), source=await store.verifyFile(profile.id,id);
        const chosen=await dialog.showSaveDialog(win,{title:'另存为 · '+asset.name,defaultPath:asset.name});
        if(chosen.canceled||!chosen.filePath)break;
        const parent=realpathSync(path.dirname(chosen.filePath));const destination=path.join(parent,path.basename(chosen.filePath));const protectedRoot=realpathSync(host.dataDirectory);
        if(destination===protectedRoot||destination.startsWith(protectedRoot+path.sep)||(existsSync(destination)&&lstatSync(destination).isSymbolicLink()))throw new Error('请选择应用数据目录以外的位置，不能覆盖本机账号或资产文件。');
        const temporary=path.join(parent,'.profiles-export-'+randomUUID()+'.tmp');
        try {
          const input=await open(source,constants.O_RDONLY|(constants.O_NOFOLLOW||0));const digest=createHash('sha256');let bytes=0;
          try{await pipeline(input.createReadStream({autoClose:false}),new Transform({transform(chunk:Buffer,_encoding,callback){digest.update(chunk);bytes+=chunk.length;callback(null,chunk);}}),createWriteStream(temporary,{flags:'wx',mode:0o600}));}
          finally{await input.close();}
          if(bytes!==asset.size||digest.digest('hex')!==asset.sha256)throw new Error('导出时原文件发生变化，已保留目标原文件，请重新选择资产。');
          if(existsSync(destination)&&lstatSync(destination).isSymbolicLink())throw new Error('目标文件已变成链接，已停止覆盖');await rename(temporary,destination);
        }
        finally { await rm(temporary,{force:true}); }
        break;
      }
      case 'source': {
        const asset=store.get(profile.id,id);
        if(!asset.source.conversationURL)throw new Error('此资产没有可打开的 ChatGPT 来源对话');
        host.openChat(profile.id,asset.source.conversationURL);break;
      }
      default: throw new Error('未知资产操作');
    }
    notify(profile.id);
  });
  ipcMain.handle('capabilities:snapshot',event=>{access(event);return {...host.catalog().snapshot(),favorites:host.state().capabilityFavorites||[]};});
  ipcMain.handle('capabilities:agent',(event,id:unknown)=>{access(event);if(typeof id!=='string')throw new Error('无效角色');return host.catalog().agent(id).instructions;});
  ipcMain.handle('capabilities:favorite',(event,id:unknown,favorite:unknown)=>{access(event);if(typeof id!=='string'||typeof favorite!=='boolean')throw new Error('收藏状态无效');host.favoriteCapability(id,favorite);});
  ipcMain.handle('capabilities:compose',(event,options)=>{access(event);return host.catalog().compose(options);});
  ipcMain.handle('assets:convert',async(event,profileId:unknown,ids:unknown,options:unknown)=>{access(event,profileId);if(!Array.isArray(ids)||!ids.every(id=>typeof id==='string')||!record(options))throw new Error('转换参数无效');return host.conversions().convert(profileId as string,ids,options as unknown as import('../shared/workbench').ConversionOptions);});
}
