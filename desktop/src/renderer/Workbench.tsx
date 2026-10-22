import { useEffect, useRef } from 'react';
import type { Snapshot } from '../shared/model';
import type { WorkbenchPanel } from '../shared/workbench';
import { WorkbenchUI } from './workbench-ui';
import './workbench.css';

export function Workbench({ state, close, panel, notice }: {state:Snapshot;close:()=>void;panel:(panel:WorkbenchPanel)=>void;notice:(message:string)=>void}) {
  const root=useRef<HTMLDivElement>(null),controller=useRef<WorkbenchUI|undefined>(undefined);
  const callbacks=useRef({close,panel,notice});callbacks.current={close,panel,notice};
  useEffect(()=>{
    if(!root.current||!state.panel)return;
    const profile=state.profiles.find(p=>p.id===state.activeProfileId)!;
    controller.current=new WorkbenchUI(root.current,window.profiles.workbench,profile,state.profiles,state.panel,{close:()=>callbacks.current.close(),panel:p=>callbacks.current.panel(p),notice:text=>callbacks.current.notice(text),persist:(profileId,value,epoch)=>window.profiles.saveWorkbench(profileId,value,epoch)});
    const shortcut=root.current.querySelector('kbd');if(shortcut)shortcut.textContent=state.platform==='win32'?'Ctrl K':'⌘ K';
    const search=()=>controller.current?.focusSearch();window.addEventListener('workbench-search',search);
    const checkpoint=(event:Event)=>{const detail=(event as CustomEvent<{pending:Promise<unknown>[]}>).detail;if(detail?.pending)detail.pending.push(controller.current?.flushView()||Promise.resolve());};window.addEventListener('profiles:checkpoint',checkpoint);
    const closeTop=()=>controller.current?.closeTop();window.addEventListener('workbench-close',closeTop);
    return()=>{window.removeEventListener('workbench-search',search);window.removeEventListener('profiles:checkpoint',checkpoint);window.removeEventListener('workbench-close',closeTop);controller.current?.dispose();controller.current=undefined;};
  },[]);
  const profileSignature=state.profiles.map(p=>`${p.id}:${p.name}:${p.color}`).join('|');
  useEffect(()=>{const profile=state.profiles.find(p=>p.id===state.activeProfileId)!;if(state.panel)controller.current?.update(profile,state.profiles,state.panel);},[state.activeProfileId,profileSignature,state.panel]);
  return <div className="native-workbench" ref={root} aria-label="资产与能力工作台"/>;
}
