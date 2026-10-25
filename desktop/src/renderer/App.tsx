import { useCallback, useEffect, useRef, useState, lazy, Suspense, type FormEvent, type CSSProperties } from 'react';
import { createRoot } from 'react-dom/client';
import { COLORS, SHELL_HEIGHT, desktopWorkspaceEnabled, type Bridge, type Command, type Snapshot } from '../shared/model';
import { Icon, Mark, type IconName } from './icons';
import './style.css';
const Workbench=lazy(()=>import('./Workbench').then(module=>({default:module.Workbench})));
import { BROWSER_LANGUAGES,DEFAULT_LANGUAGE } from '../shared/profile-preferences';
import { LoginDiagnostics } from './LoginDiagnostics';
declare global { interface Window { profiles: Bridge } }
const commonZones:Record<string,string>={'UTC':'UTC · 协调世界时','Asia/Shanghai':'上海 · 中国标准时间','Asia/Hong_Kong':'香港','Asia/Taipei':'台北','Asia/Tokyo':'东京','Asia/Singapore':'新加坡','America/New_York':'纽约','America/Los_Angeles':'洛杉矶','Europe/London':'伦敦'};
const zoneOptions=[...new Set([...Object.keys(commonZones),...Intl.supportedValuesOf('timeZone')])];
const systemZone=Intl.DateTimeFormat().resolvedOptions().timeZone;
type Editor = { kind:'add' } | { kind:'edit'; id:string };
function Tool({icon,label,disabled=false,active=false,onClick}:{icon:IconName;label:string;disabled?:boolean;active?:boolean;onClick:()=>void}) { return <button type="button" className={`tool ${active?'is-active':''}`} title={label} aria-label={label} disabled={disabled} onClick={onClick}><Icon name={icon}/></button>; }
function App() {
  const [state,setState] = useState<Snapshot>();
  const [fatal,setFatal] = useState('');
  const [diagnosticsOpen,setDiagnosticsOpen]=useState(false);
  const [editor,setEditor] = useState<Editor>();
  const [menuOpen,setMenuOpen] = useState(false);
  const menuRef = useRef(false);
  const accountButton = useRef<HTMLButtonElement>(null);
  const accountMenu = useRef<HTMLDivElement>(null);
  const [toast,setToast] = useState('');
  const [busy,setBusy] = useState(false);
  const [formError,setFormError] = useState('');
  const current = useRef<Snapshot|undefined>(undefined);
  const toastTimer = useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
  const modal = useRef<HTMLDivElement>(null);
  const dragged = useRef<string|undefined>(undefined);
  const showToast = useCallback((message:string)=>{setToast(message);clearTimeout(toastTimer.current);toastTimer.current=setTimeout(()=>setToast(''),4500);},[]);
  const send = useCallback(async(command:Command)=>{
    try { const reply=await window.profiles.command(command);if(!reply.ok){showToast(reply.error);return false;}return true; }
    catch {showToast('无法连接客户端，请重新打开窗口。');return false;}
  },[showToast]);
  const openEditor = useCallback(async(value:Editor)=>{if(await send({type:'overlay',open:true})){menuRef.current=false;setMenuOpen(false);setFormError('');setEditor(value);}},[send]);
  const closeEditor = useCallback(async()=>{setEditor(undefined);setFormError('');await send({type:'overlay',open:false});},[send]);
  const closeDiagnostics=useCallback(async()=>{setDiagnosticsOpen(false);await send({type:'overlay',open:false});},[send]);
  const openDiagnostics=useCallback(async()=>{if(await send({type:'overlay',open:true})){menuRef.current=false;setMenuOpen(false);setEditor(undefined);setDiagnosticsOpen(true);}},[send]);
  const closeAccounts = useCallback(async()=>{menuRef.current=false;setMenuOpen(false);await send({type:'overlay',open:false});},[send]);
  const toggleAccounts = useCallback(async()=>{if(menuRef.current){await closeAccounts();return;}if(await send({type:'overlay',open:true})){setEditor(undefined);menuRef.current=true;setMenuOpen(true);}},[send,closeAccounts]);
  useEffect(()=>{
    if(!menuOpen)return;
    accountMenu.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus();
    const handle=(event:KeyboardEvent)=>{
      if(event.key==='Escape'){event.preventDefault();void closeAccounts();return;}
      const items=Array.from(accountMenu.current?.querySelectorAll<HTMLElement>('button:not(:disabled)')??[]);if(!items.length)return;
      const index=items.indexOf(document.activeElement as HTMLElement);
      if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();items[(index+(event.key==='ArrowDown'?1:-1)+items.length)%items.length].focus();}
      if(event.key==='Tab'&&((event.shiftKey&&index===0)||(!event.shiftKey&&index===items.length-1))){event.preventDefault();items[event.shiftKey?items.length-1:0].focus();}
    };
    window.addEventListener('keydown',handle);return()=>window.removeEventListener('keydown',handle);
  },[menuOpen,closeAccounts]);
  useEffect(()=>{
    if(!window.profiles){setFatal('请从 ChatGPT Studio 客户端打开此界面。');return;}
    let mounted=true;
    const apply=(s:Snapshot)=>{if(mounted){current.current=s;setState(s);}};
    const unsubscribe=window.profiles.subscribe(apply);
    window.profiles.snapshot().then(s=>{apply(s);if(s.notice)showToast(s.notice);}).catch(()=>setFatal('账号空间加载失败，请重新打开客户端。'));
    const unshortcut=window.profiles.onShortcut(key=>{if(key==='add-profile')void openEditor({kind:'add'});else if(key==='toggle-accounts')void toggleAccounts();else if(key==='close-workbench')window.dispatchEvent(new Event('workbench-close'));else if(key==='workbench-search')window.dispatchEvent(new Event('workbench-search'));else if(key.startsWith('notice:'))showToast(key.slice(7));});
    return ()=>{mounted=false;unsubscribe();unshortcut();clearTimeout(toastTimer.current);};
  },[openEditor,showToast,toggleAccounts]);
  useEffect(()=>{
    if(!editor)return;
    const prior=document.activeElement as HTMLElement|null;
    modal.current?.querySelector<HTMLInputElement>('input')?.focus();
    const handler=(event:KeyboardEvent)=>{
      if(event.key==='Escape'&&!busy){event.preventDefault();void closeEditor();}
      if(event.key==='Tab'){
        const items=Array.from(modal.current?.querySelectorAll<HTMLElement>('input,button:not(:disabled),[tabindex="0"]')??[]);
        const first=items[0],last=items.at(-1);
        if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
        else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
      }
    };
    window.addEventListener('keydown',handler);
    return()=>{window.removeEventListener('keydown',handler);prior?.focus();};
  },[editor,busy,closeEditor]);
  if(fatal)return <div className="boot"><Mark size={48}/><h1>暂时无法打开工作区</h1><p>{fatal}</p></div>;
  if(!state)return <div className="boot"><Mark size={48}/><p>正在恢复账号空间…</p></div>;
  const profile=state.profiles.find(p=>p.id===state.activeProfileId)!;
  const tabs=state.tabs.filter(t=>t.profileId===profile.id);
  const tab=tabs.find(t=>t.id===profile.activeTabId)!;
  const status=state.tabStatus[tab.id];
  const editing=editor?.kind==='edit'?state.profiles.find(p=>p.id===editor.id):undefined;
  const style={'--shell-height':SHELL_HEIGHT+'px'} as CSSProperties;
  const submit=async(event:FormEvent<HTMLFormElement>)=>{
    event.preventDefault();if(busy)return;setBusy(true);setFormError('');
    const form=new FormData(event.currentTarget);
    const fields={name:String(form.get('name')||'').trim(),email:String(form.get('email')||'').trim(),color:String(form.get('color')||COLORS[0]),...(desktopWorkspaceEnabled(current.current?.platform)?{language:String(form.get('language')||'system'),timeZone:String(form.get('timeZone')||'system')}:{})};
    try{
      const result=await window.profiles.command(editing?{type:'profile:edit',id:editing.id,...fields}:{type:'profile:add',...fields});
      if(result.ok){await closeEditor();showToast(editing?'账号备注已保存':'账号空间已创建，请在网页内登录。');}else setFormError(result.error);
    }catch{setFormError('保存失败，请重试。');}finally{setBusy(false);}
  };
  const accountAction=async(type:'profile:clear'|'profile:remove',id:string)=>{setBusy(true);if(await send({type,id}))await closeEditor();setBusy(false);};
  return <main className="workspace" data-platform={state.platform} data-fullscreen={state.fullScreen} style={style}>
    <header className="titlebar" aria-label="客户端标题栏">
      <div className="traffic-space" aria-hidden="true"/>
      <div className="tabs" role="tablist" aria-label={`${profile.name}的标签页`}>
        {tabs.map(t=><div key={t.id} className={`tab ${t.id===tab.id?'active':''} ${t.pinned?'pinned':''}`} draggable onContextMenu={e=>{e.preventDefault();void send({type:'tab:menu',id:t.id});}} onDragStart={()=>{dragged.current=t.id;}} onDragEnd={()=>{dragged.current=undefined;}} onDragOver={e=>e.preventDefault()} onDrop={e=>{e.preventDefault();const from=dragged.current;if(!from||from===t.id)return;const ids=tabs.map(x=>x.id),index=ids.indexOf(from),target=ids.indexOf(t.id);if(index<0||target<0)return;ids.splice(index,1);ids.splice(target,0,from);void send({type:'tab:reorder',ids});}}>
          <button className="tab-select" role="tab" aria-selected={t.id===tab.id} title={`${profile.name} · ${t.title}`} onClick={()=>void send({type:'tab:activate',id:t.id})}>
            {state.tabStatus[t.id]?.loading?<span className="spinner"/>:<Icon name="chat" size={13}/>}<span>{t.title||'ChatGPT'}</span>{t.pinned&&<Icon name="pin" size={12}/>}</button>
          <button className="tab-close" title="关闭标签页" aria-label={`关闭标签 ${t.title}`} onClick={()=>void send({type:'tab:close',id:t.id})}><Icon name="close" size={13}/></button>
        </div>)}
      </div>
      <Tool icon="plus" label="新建标签页" onClick={()=>void send({type:'tab:new'})}/>
      <div className="drag-space" aria-label="窗口拖动区域"/>
      {desktopWorkspaceEnabled(state.platform)&&<><button className={'workspace-action '+(state.panel==='assets'?'active':'')} aria-label="资产中心" aria-pressed={state.panel==='assets'} onClick={()=>void send({type:'panel',panel:state.panel==='assets'?undefined:'assets'})}><Icon name="folder" size={14}/>资产</button><button className={'workspace-action '+(state.panel==='skills'||state.panel==='agents'?'active':'')} aria-label="能力库" aria-pressed={state.panel==='skills'||state.panel==='agents'} onClick={()=>void send({type:'panel',panel:state.panel==='skills'||state.panel==='agents'?undefined:'skills'})}><Icon name="sparkles" size={14}/>能力库</button></>}
      <button ref={accountButton} className="current-account" aria-label="切换账号" aria-haspopup="menu" aria-expanded={menuOpen} disabled={busy} onClick={()=>void toggleAccounts()} title={profile.name+(profile.email?' · '+profile.email:'')}><span className="dot" style={{background:profile.color}}/><span>{profile.name}</span><Icon name="chevron" size={12}/></button>
    </header>
    <section className="web-stage" aria-label="网页区域">
      {state.recoveryAccess?.blocking?<div className="page-state recovery-consent" aria-label="本地恢复授权"><Mark size={38}/><h1>{state.recoveryAccess.phase==='working'?'正在解锁本地恢复':'恢复上次的工作区'}</h1><p>草稿、阅读位置与离线页面由这台 Mac 的钥匙串加密保护。继续后，系统可能请求授权或 Mac 登录密码。</p><p>不会读取 Chrome 密码。取消后仍可打开网页，已有登录和恢复原件都会保留。</p>{state.recoveryAccess.message&&<p role="alert">{state.recoveryAccess.message}</p>}<div className="recovery-actions"><button className="primary" disabled={state.recoveryAccess.phase==='working'} onClick={()=>void send({type:'recovery:authorize'})}>{state.recoveryAccess.protected?'解锁本地恢复':'继续恢复'}</button><button className="secondary" disabled={state.recoveryAccess.phase==='working'} onClick={()=>void send({type:'recovery:defer'})}>先打开网页</button></div></div>:state.offline?<div className="offline-page" aria-label="本地页面快照">
        <div className="offline-banner"><div><strong>上次的页面 · 只读快照</strong><span>{new Date(state.offline.capturedAt).toLocaleString('zh-CN')} · {status?.loading?'正在连接原网页…':status?.error?'暂时无法联网，内容已保留':'正在恢复网页…'}</span></div><button className="secondary" onClick={()=>void send({type:'offline:retry'})}>重新连接</button><button className="secondary" onClick={()=>void send({type:'offline:live'})}>打开当前网页</button></div>
        <div className="offline-reading"><h1>{state.offline.title||'上次的对话'}</h1>
          {state.offline.image&&<img className="offline-viewport" src={state.offline.image} alt="退出前最后可见的网页画面"/>}
          <details open={!state.offline.image}><summary>阅读已保存的对话文字</summary>{state.offline.messages.map((message,index)=><article key={index} className={'offline-message '+message.role}><span>{message.role==='user'?'你':message.role==='assistant'?'ChatGPT':'对话'}</span><p>{message.text}</p></article>)}{state.offline.truncated&&<p className="offline-note">对话较长，仅保存了部分文字。联网后查看完整原文。</p>}</details>
          <p className="offline-note">这是本机加密保存的副本。图片保留在最后可见画面中；链接、附件和消息操作需连接原网页后使用。不会自动发送消息。</p>
        </div>
      </div>:status?.error?<div className="page-state" role="alert"><span className="page-state-icon"><Icon name="alert" size={28}/></span><h1>网页暂时无法打开</h1><p>{profile.name} 的账号空间已保留，可以重新加载。</p><code>{status.error}</code><button className="primary" onClick={()=>void send({type:'reload'})}><Icon name="refresh" size={16}/>重新加载</button></div>:<div className="page-state loading-state"><Mark size={38}/><h1>正在打开 ChatGPT</h1><p>{profile.name} · 独立账号空间</p><span className="spinner"/></div>}
      {(editor||menuOpen||diagnosticsOpen)&&state.overlayImage&&<img className="page-preview" src={state.overlayImage} alt="" aria-hidden="true"/>}
    </section>
    {desktopWorkspaceEnabled(state.platform)&&!state.recoveryAccess?.blocking&&state.panel&&<Suspense fallback={<div className="workbench-loading" role="status">正在打开工作台…</div>}><Workbench state={state} close={()=>void send({type:'panel'})} panel={panel=>void send({type:'panel',panel})} notice={showToast}/></Suspense>}
    {!state.recoveryAccess?.blocking&&['deferred','error'].includes(state.recoveryAccess?.phase||'')&&<div className="recovery-banner" role="status">旧恢复原件保留，本次草稿暂未保存。<button onClick={()=>void send({type:'recovery:authorize'})}>重新授权</button></div>}
    {toast&&<div className="toast" role="status"><Icon name="check" size={15}/>{toast}<button aria-label="关闭提示" onClick={()=>setToast('')}><Icon name="close" size={13}/></button></div>}
    {menuOpen&&<div className="menu-backdrop" onMouseDown={e=>{if(e.target===e.currentTarget)void closeAccounts();}}>
      <div className="account-menu" role="menu" aria-label="账号空间" ref={accountMenu}>
        <div className="menu-heading">账号空间<span>本机独立保存</span></div>
        <div className="account-list">{state.profiles.map(p=><button key={p.id} className={'account-item '+(p.id===profile.id?'selected':'')} role="menuitemradio" aria-label={'切换到 '+p.name} aria-checked={p.id===profile.id} onClick={async()=>{if(await send({type:'profile:activate',id:p.id}))await closeAccounts();}}>
          <span className="dot" style={{background:p.color}}/><span className="account-copy"><strong>{p.name}</strong><small>{p.email||'未填写邮箱备注'}</small></span>{p.id===profile.id&&<Icon name="check" size={14}/>}
        </button>)}</div>
        <div className="menu-separator"/>
        {state.platform==='darwin'&&<button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'recovery:settings'});}}><Icon name="settings" size={15}/>隐私与本地恢复<span>{state.recoveryAccess?.protected?'Touch ID':'钥匙串'}</span></button>}
        <button className="menu-action" role="menuitem" onClick={()=>void openEditor({kind:'add'})}><Icon name="plus" size={15}/>添加账号<span>{state.platform==='darwin'?'⌘⇧N':'Ctrl+Shift+N'}</span></button>
        <button className="menu-action" role="menuitem" aria-label={'管理 '+profile.name} onClick={()=>void openEditor({kind:'edit',id:profile.id})}><Icon name="settings" size={15}/>管理当前账号</button>
        {state.platform==='win32'&&<>
          <div className="menu-separator"/>
          <button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'reload'});}}><Icon name="refresh" size={15}/>重新加载网页<span>Ctrl+R</span></button>
          <button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'back'});}}>后退<span>Ctrl+[</span></button>
          <button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'forward'});}}>前进<span>Ctrl+]</span></button>
          <button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'home'});}}>返回 ChatGPT 首页</button>
          <button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'tab:restore'});}}>恢复关闭的标签页<span>Ctrl+Shift+T</span></button>
          <button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'fullscreen'});}}>进入/退出全屏<span>F11</span></button>
        </>}
        {desktopWorkspaceEnabled(state.platform)&&<button className="menu-action" role="menuitem" onClick={()=>void openDiagnostics()}><Icon name="shield" size={15}/>登录与链接记录</button>}
        {state.platform==='darwin'&&<button className="menu-action" role="menuitem" onClick={async()=>{await closeAccounts();await send({type:'update:check'});}}><Icon name="refresh" size={15}/>{state.update?.phase==='downloading'?`后台更新 ${state.update.progress||0}%`:state.update?.phase==='ready'?`更新 ${state.update.version} 已准备`:'检查更新'}<span>{state.version}</span></button>}
        {state.platform==='darwin'&&state.update?.phase==='ready'&&<button className="menu-action" role="menuitemcheckbox" aria-checked={state.update.idleEnabled===true} onClick={()=>void send({type:'update:idle',enabled:!state.update?.idleEnabled})}><Icon name="check" size={15}/>应用空闲 30 分钟后安装<span>{state.update.idleEnabled?'已开启':'已关闭'}</span></button>}
        <div className="menu-foot">账号名称与邮箱为手动备注。<br/>每个账号拥有自己的登录状态和标签页。</div>
      </div>
    </div>}
    {diagnosticsOpen&&<LoginDiagnostics state={state} close={closeDiagnostics} notice={showToast}/>}
    {editor&&<div className="modal-backdrop" onMouseDown={event=>{if(event.target===event.currentTarget&&!busy)void closeEditor();}}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="editor-title" ref={modal}>
        <div className="modal-header"><span className="modal-symbol"><Icon name={editing?'edit':'plus'} size={22}/></span><button className="tool" aria-label="关闭账号管理" disabled={busy} onClick={()=>void closeEditor()}><Icon name="close"/></button></div>
        <h1 id="editor-title">{editing?'管理账号空间':'添加账号空间'}</h1><p className="modal-description">{editing?'设置清晰的标识，切换时一眼认出账号。':'创建独立的浏览器空间，然后在 ChatGPT 网页内登录。'}</p>
        <form key={editing?.id||'new'} onSubmit={event=>void submit(event)}><div className="account-form-body">
          <label>账号名称<input name="name" placeholder="例如：个人账号、工作账号" maxLength={40} required defaultValue={editing?.name||''} disabled={busy}/></label>
          <label>邮箱备注 <span>选填</span><input name="email" placeholder="name@example.com" maxLength={120} defaultValue={editing?.email||''} disabled={busy}/></label>
          {desktopWorkspaceEnabled(state.platform)&&<div className="profile-preferences"><label>浏览器语言<select aria-label="浏览器语言" name="language" defaultValue={editing?.language||(editing?'system':DEFAULT_LANGUAGE)} disabled={busy}>{BROWSER_LANGUAGES.map(l=><option key={l.id} value={l.id}>{l.name}</option>)}</select></label><label>时区<select aria-label="时区" name="timeZone" defaultValue={editing?.timeZone||'system'} disabled={busy}><option value="system">跟随系统（{systemZone}）</option>{zoneOptions.map(zone=><option key={zone} value={zone}>{commonZones[zone]?commonZones[zone]+' · ':''}{zone}</option>)}</select></label><p>{editing?'修改后从新建标签页起生效，现有网页和它的登录窗口沿用原设置。':'只应用于这个账号的网页，不改变电脑的语言和时间。'}网页自行保存的语言偏好可能优先。</p></div>}
          <fieldset disabled={busy}><legend>标识颜色</legend><div className="color-options">{COLORS.map((color,i)=><label key={color} className="color-option" title={`颜色 ${i+1}`} style={{'--swatch':color} as CSSProperties}><input type="radio" name="color" value={color} defaultChecked={(editing?.color||COLORS[0])===color} aria-label={`颜色 ${i+1}`}/><span/></label>)}</div></fieldset>
          <div className="identity-note"><Icon name="shield" size={17}/><p>名称与邮箱是手动备注，实际登录身份以网页内显示为准。密码由登录网页处理。</p></div>
          {formError&&<p className="form-error" role="alert">{formError}</p>}
          </div><div className="modal-actions"><button type="button" className="secondary" disabled={busy} onClick={()=>void closeEditor()}>取消</button><button type="submit" className="primary" disabled={busy}>{busy?'处理中…':editing?'保存修改':'创建并打开网页'}</button></div>
        </form>
        {editing&&<div className="account-maintenance"><div className="account-order"><span>账号排序</span><Tool icon="arrowUp" label="账号上移" disabled={busy||state.profiles[0].id===editing.id} onClick={()=>void send({type:'profile:move',id:editing.id,direction:-1})}/><Tool icon="arrowDown" label="账号下移" disabled={busy||state.profiles.at(-1)?.id===editing.id} onClick={()=>void send({type:'profile:move',id:editing.id,direction:1})}/></div><div className="danger-actions"><button disabled={busy} onClick={()=>void accountAction('profile:clear',editing.id)}>清除本机登录数据</button><button disabled={busy||state.profiles.length===1} title={state.profiles.length===1?'至少保留一个账号空间':''} onClick={()=>void accountAction('profile:remove',editing.id)}>移除账号空间</button></div></div>}
      </div>
    </div>}
  </main>;
}
createRoot(document.getElementById('root')!).render(<App/>);
