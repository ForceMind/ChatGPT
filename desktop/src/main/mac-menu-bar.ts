import {Menu,Tray,nativeImage} from 'electron';
import type {Command} from '../shared/model';

export interface MenuBarState {
 profiles:{id:string;name:string;email:string}[];
 activeProfileId:string;
 windowVisible:boolean;
 canInteract:boolean;
 canCheckUpdates:boolean;
 quitting:boolean;
}
export interface MenuBarHost {
 read():MenuBarState;
 activity():void;
 toggleWindow():void;
 command(command:Command):void;
 checkUpdates():void;
 quit():void;
}

/** Main-process native status item. It never owns or recreates browser spaces. */
export class MacMenuBar {
 private readonly tray:Tray;
 private readonly image:Electron.NativeImage;
 private currentMenu:Menu|undefined;
 private signature='';
 private open=false;
 constructor(imagePath:string,private readonly host:MenuBarHost){
  this.image=nativeImage.createFromPath(imagePath);
  if(this.image.isEmpty())throw Error('菜单栏图标资源无法读取');
  this.image.setTemplateImage(true);
  this.tray=new Tray(this.image);
  this.refresh();
 }
 get isOpen():boolean{return this.open;}
 /** Native menu access is confined to the privileged main-process module. */
 get menu():Menu|undefined{return this.currentMenu;}
 refresh():void{
  if(this.tray.isDestroyed())return;
  const state=this.host.read(),signature=JSON.stringify(state);if(signature===this.signature)return;this.signature=signature;
  const active=state.profiles.find(p=>p.id===state.activeProfileId);
  const run=(command:Command)=>()=>{this.host.activity();if(this.host.read().canInteract)this.host.command(command);};
  const template:Electron.MenuItemConstructorOptions[]=[
   {id:'menubar-status',label:`ChatGPT Studio · ${active?.name||'账号空间'}`,enabled:false},
   {id:'menubar-window',label:state.windowVisible?'隐藏主窗口（保留后台）':'显示主窗口',click:()=>{this.host.activity();this.host.toggleWindow();}},
   {id:'menubar-new-tab',label:'新建对话',enabled:state.canInteract,click:run({type:'tab:new'})},
   {type:'separator'},
   {id:'menubar-profiles',label:'切换账号',enabled:state.canInteract,submenu:state.profiles.map(p=>({id:'menubar-profile-'+p.id,label:p.name+(p.email?' · '+p.email:''),type:'radio' as const,checked:p.id===state.activeProfileId,click:run({type:'profile:activate',id:p.id})}))},
   {id:'menubar-assets',label:'资产工作台',enabled:state.canInteract,click:run({type:'panel',panel:'assets'})},
   {id:'menubar-skills',label:'Skill 能力库',enabled:state.canInteract,click:run({type:'panel',panel:'skills'})},
   {type:'separator'},
   {id:'menubar-updates',label:state.canCheckUpdates?'检查更新…':'检查更新（安装版可用）',enabled:state.canCheckUpdates,click:()=>{this.host.activity();this.host.checkUpdates();}},
   {id:'menubar-quit',label:'退出 ChatGPT Studio',enabled:!state.quitting,click:()=>{this.host.activity();this.host.quit();}}
  ];
  const menu=Menu.buildFromTemplate(template);
  menu.on('menu-will-show',()=>{this.open=true;this.host.activity();});
  menu.on('menu-will-close',()=>{this.open=false;this.host.activity();});
  this.currentMenu=menu;this.tray.setContextMenu(menu);this.tray.setToolTip(`ChatGPT Studio · ${active?.name||'账号空间'}`);
 }
 inspect():{bounds:Electron.Rectangle;imageSize:Electron.Size;scaleFactors:number[];template:boolean;destroyed:boolean}{
  return{bounds:this.tray.getBounds(),imageSize:this.image.getSize(),scaleFactors:this.image.getScaleFactors(),template:this.image.isTemplateImage(),destroyed:this.tray.isDestroyed()};
 }
 dispose():void{this.open=false;if(!this.tray.isDestroyed())this.tray.destroy();this.currentMenu=undefined;}
}
