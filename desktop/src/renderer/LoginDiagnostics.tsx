import {useEffect,useRef,useState} from 'react';
import type {Snapshot} from '../shared/model';
import {LINK_KIND_LABEL,LINK_RESULT_LABEL,type LinkRecord} from '../shared/diagnostics';
import {Icon} from './icons';
import './login-diagnostics.css';
export function LoginDiagnostics({state,close,notice}:{state:Snapshot;close:()=>void;notice:(text:string)=>void}) {
  const [items,setItems]=useState<LinkRecord[]>([]),[error,setError]=useState(''),[filter,setFilter]=useState(state.activeProfileId),[ready,setReady]=useState(false);
  const root=useRef<HTMLDivElement>(null);
  useEffect(()=>{let live=true;const load=()=>window.profiles.diagnostics.list().then(records=>{if(live){setItems(records);setReady(true);setError('');}}).catch(e=>{if(live)setError(e instanceof Error?e.message:'登录记录读取失败');});void load();const off=window.profiles.diagnostics.subscribe(()=>void load());return()=>{live=false;off();};},[]);
  useEffect(()=>{root.current?.querySelector<HTMLButtonElement>('button')?.focus();const key=(e:KeyboardEvent)=>{if(e.key==='Escape'){e.preventDefault();close();}if(e.key==='Tab'){const nodes=[...root.current!.querySelectorAll<HTMLElement>('button:not(:disabled),select,a[href],summary')];const a=nodes[0],b=nodes.at(-1);if(e.shiftKey&&document.activeElement===a){e.preventDefault();b?.focus();}else if(!e.shiftKey&&document.activeElement===b){e.preventDefault();a?.focus();}}};window.addEventListener('keydown',key);return()=>window.removeEventListener('keydown',key);},[close]);
  const filtered=items.filter(i=>!filter||i.profileId===filter);
  const copy=async()=>{try{await navigator.clipboard.writeText(filtered.map(i=>`${new Date(i.time).toLocaleString()} | ${state.profiles.find(p=>p.id===i.profileId)?.name||'已移除的账号'} | ${LINK_KIND_LABEL[i.kind]} | ${LINK_RESULT_LABEL[i.result]}\n目标：${i.destination}；来源：${i.source}\n${i.reason}`).join('\n\n'));notice('已复制脱敏记录');}catch{notice('复制失败，请手动选择记录复制。');}};
  return <div className="modal-backdrop" onMouseDown={e=>{if(e.target===e.currentTarget)close();}}><div ref={root} className="login-diagnostics" role="dialog" aria-modal="true" aria-labelledby="login-record-title">
    <div className="login-record-header"><div><h1 id="login-record-title">登录与链接记录</h1><p>最近 200 条，仅保存在本机。链接只保留协议和站点，不保存路径、参数与验证码。</p></div><button className="tool" aria-label="关闭登录记录" onClick={close}><Icon name="close"/></button></div>
    <details className="login-help"><summary>通行密钥与登录帮助</summary><div>
      <p><strong>Apple「密码」/ iCloud 钥匙串：</strong>当前版本尚未接入系统通行密钥选择器，不能保证读取已有密钥。登录网页若提供密码、验证码或手机/安全密钥等其他方式，可选择相应方式继续。</p>
      <p><strong>Chrome / Google Password Manager：</strong>App 的账号空间与 Chrome 独立，不会自动继承 Chrome 保存的通行密钥。在 Chrome 登录也不会同步此 App 的登录状态。可在 Chrome 的「密码和自动填充 → Google 密码管理工具」确认密钥保存位置。</p>
      <p>网页若提供「使用其他设备的通行密钥」，按网页和系统提示操作；当前 App 对该流程仍待真实设备验证。无需将密码、验证码或密钥交给 App。</p>
      <p>账号名称和邮箱是你填写的备注，实际身份以登录网页为准。当前正常使用的登录方式可以继续使用。</p>
      <p>Google 官方通行密钥说明（复制链接，在浏览器查看）<button className="secondary" onClick={()=>void navigator.clipboard.writeText('https://support.google.com/chrome/answer/13168025?hl=zh-Hans').then(()=>notice('已复制帮助链接')).catch(()=>notice('复制失败'))}>复制帮助链接</button></p>
    </div></details>
    <div className="login-record-tools"><label>账号<select aria-label="筛选登录记录账号" value={filter} onChange={e=>setFilter(e.target.value)}><option value="">全部账号</option>{state.profiles.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label><span>{filtered.length} 条</span><button className="secondary" disabled={!filtered.length} onClick={()=>void copy()}>复制脱敏记录</button><button className="secondary" disabled={!items.length} onClick={()=>void window.profiles.diagnostics.clear().catch(e=>setError(String(e)))}>清空全部记录</button></div>
    {error&&<p className="form-error" role="alert">{error}</p>}
    <div className="login-record-list">{filtered.length?filtered.map(i=><article key={i.id}><div className="login-record-meta"><strong>{LINK_RESULT_LABEL[i.result]}</strong><span>{LINK_KIND_LABEL[i.kind]}</span><span>{state.profiles.find(p=>p.id===i.profileId)?.name||'已移除的账号'}</span><time>{new Date(i.time).toLocaleString()}</time></div><code>{i.destination}</code><p>{i.reason}</p><small>来源站点：{i.source}</small></article>):<div className="login-record-empty">{ready?'暂无记录。下一次拦截链接或访问登录站点时，会在这里显示。':'正在读取记录…'}</div>}</div>
  </div></div>;
}
