import type {WorkbenchViewState} from '../shared/recovery';
import template from './workbench-template.html?raw';
import type { Profile } from '../shared/model';
import type { AssetAction, AssetItem, AssetPreview, WorkbenchAPI, WorkbenchPanel, ConversionOptions } from '../shared/workbench';
import type { AgentSummary, CapabilitySnapshot, SkillDefinition } from '../shared/capabilities';
import { wbIcon as icon } from './workbench-icons';
import { handoffStages,handoffStageNames,handoffStatusNames,type HandoffJob } from '../shared/handoff';
import type { HandoffRequest } from '../shared/workbench';
import { conversionFormats } from '../shared/conversion-formats';

const esc = (v: unknown) => String(v ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!));
const classes = (value: string) => value.split(' ').filter(Boolean).map(c=>'aw-'+c).join(' ');
const size = (n: number) => n<1024?`${n} B`:n>=1048576?`${(n/1048576).toFixed(1)} MB`:`${(n/1024).toFixed(1)} KB`;
const statusName: Record<string,string> = {ready:'可用',downloading:'下载中',paused:'已暂停',processing:'处理中',interrupted:'已中断',cancelled:'已取消',failed:'失败',missing:'文件丢失',changed:'文件已变更'};
const filters = [{id:'all',name:'全部资产',icon:'grid'},{id:'image',name:'图片',icon:'image'},{id:'web',name:'网页',icon:'web'},{id:'document',name:'文档',icon:'document'},{id:'data',name:'数据与代码',icon:'code'},{id:'favorite',name:'收藏',icon:'star'},{id:'converted',name:'最近转换',icon:'convert'},{id:'trash',name:'回收站',icon:'trash'}];
function selectors(value: string): string { return value.replace(/#([a-zA-Z][\w-]*)/g,'#wb-$1').replace(/\.([a-zA-Z][\w-]*)/g,'.aw-$1'); }
function message(error: unknown): string { return (error instanceof Error?error.message:'操作失败，请重试').replace(/^Error invoking remote method '[^']+':\s*(Error:\s*)?/,''); }
type ViewOptions = {close:()=>void;panel:(panel:WorkbenchPanel)=>void;notice:(text:string)=>void;persist:(profileId:string,state:WorkbenchViewState,epoch:number)=>Promise<void>};

export class WorkbenchUI {
  private assets: AssetItem[] = [];
  private catalog?: CapabilitySnapshot;
  private selected?: string;
  private explicitSelection = false;
  private checked = new Set<string>();
  private filter = 'all'; private query=''; private sort='recent'; private layout='grid';
  private libraryKind:'skills'|'agents'='skills'; private libraryFilter='all'; private libraryQuery=''; private librarySelected?:string;
  private favoritesOnly=false; private capabilityFavorites=new Set<string>();
  private panel:WorkbenchPanel; private profiles:Profile[]; private profile:Profile;
  private generation=0; private previewGeneration=0; private disposed=false; private loaded=false;
  private unsubscribe:()=>void; private lastFocus?:HTMLElement; private taskSkill?:string; private taskAgent?:string;
  private previewAsset?:AssetItem;
  private jobs:HandoffJob[]=[];private jobView?:'list'|'detail';private currentJob?:string;private submitting=false;private taskRequestId='';private unsubscribeJobs:()=>void;
  private modalVersion=0;
  private detailOpen=false;
  private detailOrigin?:HTMLElement;
  private detailTimer?:ReturnType<typeof setTimeout>;
  private readonly detailIdentities=new Map<string,string>();
  private readonly narrow=window.matchMedia('(max-width:1180px)');
  private readonly syncDetails=():void=>{
    const drawer=this.narrow.matches&&this.detailOpen;
    this.root.classList.toggle('aw-detail-open',this.detailOpen);
    for(const kind of ['assets','library']){
      const assets=kind==='assets',active=assets?this.panel==='assets':this.panel!=='assets';
      const pane=this.$(assets?'#asset-detail':'#capability-detail');
      if(drawer&&active){pane.setAttribute('role','dialog');pane.setAttribute('aria-modal','true');}else{pane.removeAttribute('role');pane.removeAttribute('aria-modal');}
      const parent=this.$(assets?'#workbench':'#capability-page');
      parent.querySelectorAll<HTMLElement>(':scope>.aw-sidebar,:scope>.aw-asset-main,:scope>.aw-capability-main').forEach(e=>{e.inert=drawer&&active;});
    }
  };
  private openDetails(origin:HTMLElement,delay=0):void{
    clearTimeout(this.detailTimer);const profile=this.profile.id,panel=this.panel;
    const open=()=>{if(this.disposed||this.profile.id!==profile||this.panel!==panel)return;this.detailOrigin=origin;this.detailOpen=true;this.syncDetails();if(this.narrow.matches)this.$(this.panel==='assets'?'#asset-detail .detail-close':'#capability-detail .detail-close').focus();};
    if(delay&&this.narrow.matches)this.detailTimer=setTimeout(open,delay);else open();
  }
  private closeDetails():void{clearTimeout(this.detailTimer);this.detailOpen=false;this.syncDetails();if(this.detailOrigin?.isConnected)this.detailOrigin.focus();}
  private structureDetail(pane:HTMLElement):void{
    const header=pane.querySelector<HTMLElement>('.aw-detail-topline')||document.createElement('div');header.className='aw-detail-topline';
    if(!header.children.length){const label=document.createElement('span');label.textContent=pane.id==='wb-asset-detail'?'文件详情':'能力详情';header.append(label);}
    const close=document.createElement('button');close.type='button';close.className='aw-icon-button aw-detail-close';close.dataset.action='close-details';close.setAttribute('aria-label','关闭详情抽屉');close.innerHTML=icon('close');header.append(close);
    const body=document.createElement('div');body.className='aw-detail-body';const footer=document.createElement('div');footer.className='aw-detail-small-actions aw-detail-footer';
    for(const child of [...pane.childNodes]){
      if(child===header)continue;
      if(child instanceof HTMLElement&&child.classList.contains('aw-detail-small-actions')&&child.querySelector('[data-action="rename"],[data-action="reveal"],[data-action="trash-current"],[data-action="export"]')){while(child.firstChild)footer.append(child.firstChild);}
      else body.append(child);
    }
    pane.replaceChildren(header,body,...(footer.children.length?[footer]:[]));this.syncDetails();
  }
  private assetMenu?:HTMLDivElement;
  private closeAssetMenu():void{this.assetMenu?.remove();this.assetMenu=undefined;}
  private readonly contextMenu=(event:MouseEvent):void=>{
    const element=event.target instanceof Element?event.target:undefined,card=element?.closest<HTMLElement>('[data-asset]');if(!card)return;
    event.preventDefault();this.closeAssetMenu();const item=this.assets.find(a=>a.id===card.dataset.asset);if(!item)return;
    if(!this.checked.has(item.id))this.checked.clear();this.selected=item.id;this.explicitSelection=true;
    this.$('#selection-bar').hidden=!this.checked.size;this.root.querySelectorAll<HTMLElement>('[data-check]').forEach(button=>{const checked=this.checked.has(button.dataset.check!);button.setAttribute('aria-pressed',String(checked));button.innerHTML=checked?icon('check'):'';});
    this.root.querySelectorAll<HTMLElement>('[data-asset]').forEach(node=>{node.classList.toggle('aw-selected',node.dataset.asset===item.id);node.classList.toggle('aw-checked',this.checked.has(node.dataset.asset!));});this.renderDetail();
    const ids=this.checked.size?[...this.checked]:[item.id],profile=this.profile.id,multiple=ids.length>1,ready=item.status==='ready'&&!item.trashedAt;
    const actions:{label:string;run:()=>Promise<unknown>|void;disabled?:boolean}[]=[
      {label:'打开预览',run:()=>this.preview(item),disabled:multiple||!ready},
      {label:'使用系统应用打开',run:()=>this.act({type:'open-external',id:item.id}),disabled:multiple||!ready},
      {label:'交给 GPT',run:()=>this.openTask(this.assets.filter(a=>ids.includes(a.id))),disabled:!ready},
      {label:'重命名',run:()=>this.edit(),disabled:multiple},
      {label:'复制文件名',run:()=>this.act({type:'copy-name',id:item.id}),disabled:multiple},
      {label:'另存为…',run:()=>this.act({type:'export',id:item.id}),disabled:multiple||!ready},
      {label:navigator.platform.startsWith('Win')?'在文件资源管理器中显示':'在 Finder 中显示',run:()=>this.act({type:'reveal',id:item.id}),disabled:multiple||!ready},
      {label:item.trashedAt?'恢复文件':`移到回收站${multiple?'（'+ids.length+' 项）':''}`,run:()=>this.act({type:'trash',ids,trashed:!item.trashedAt})}
    ];
    const menu=document.createElement('div');menu.className='aw-asset-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-label','资产操作');menu.tabIndex=-1;
    for(const action of actions){const button=document.createElement('button');button.setAttribute('role','menuitem');button.textContent=action.label;button.disabled=!!action.disabled;button.onclick=()=>{this.closeAssetMenu();if(profile!==this.profile.id)return;void Promise.resolve().then(action.run).catch(error=>this.notify(error));};menu.append(button);}
    this.root.append(menu);this.assetMenu=menu;const rect=menu.getBoundingClientRect();menu.style.left=Math.max(8,Math.min(event.clientX,innerWidth-rect.width-8))+'px';menu.style.top=Math.max(8,Math.min(event.clientY,innerHeight-rect.height-8))+'px';menu.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    menu.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();this.closeAssetMenu();card.focus();}if(['ArrowDown','ArrowUp'].includes(e.key)){e.preventDefault();const buttons=[...menu.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')],i=buttons.indexOf(document.activeElement as HTMLButtonElement);buttons[(i+(e.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length]?.focus();}});
  };
  private detailSignature='';
  private savedViews=new Map<string,WorkbenchViewState>();private viewTimer?:ReturnType<typeof setTimeout>;private restoringPreview?:string;private assetScroll=0;private libraryScroll=0;
  private scroller(kind='assets'):HTMLElement|null{return this.root.querySelector(kind==='assets'?'.aw-asset-scroll':'.aw-capability-scroll');}
  private captureView():WorkbenchViewState{if(this.panel==='assets')this.assetScroll=this.scroller()?.scrollTop||0;else this.libraryScroll=this.scroller('library')?.scrollTop||0;return{filter:this.filter,query:this.query.slice(0,300),sort:this.sort as WorkbenchViewState['sort'],layout:this.layout as WorkbenchViewState['layout'],selected:this.selected,libraryKind:this.libraryKind,libraryFilter:this.libraryFilter,libraryQuery:this.libraryQuery.slice(0,300),librarySelected:this.librarySelected,favoritesOnly:this.favoritesOnly,preview:this.previewAsset?.id,scroll:this.assetScroll,libraryScroll:this.libraryScroll};}
  flushView():Promise<void>{clearTimeout(this.viewTimer);const state=this.captureView();this.savedViews.set(this.profile.id,state);return this.options.persist(this.profile.id,state,this.profile.recoveryEpoch||0);}
  private readonly scheduleView=():void=>{clearTimeout(this.viewTimer);this.viewTimer=setTimeout(()=>{void this.flushView().catch(error=>this.notify(error));},300);};
  private restoreView(profile:Profile):void{const v=this.savedViews.get(profile.id)||profile.workspace?.workbench;this.filter=v?.filter||'all';this.query=v?.query||'';this.sort=v?.sort||'recent';this.layout=v?.layout||'grid';this.selected=v?.selected;this.explicitSelection=!!v?.selected;this.libraryKind=v?.libraryKind||'skills';this.libraryFilter=v?.libraryFilter||'all';this.libraryQuery=v?.libraryQuery||'';this.librarySelected=v?.librarySelected;this.favoritesOnly=!!v?.favoritesOnly;this.restoringPreview=v?.preview;this.assetScroll=v?.scroll||0;this.libraryScroll=v?.libraryScroll||0;this.$<HTMLInputElement>('#asset-search').value=this.query;this.$<HTMLSelectElement>('#asset-sort').value=this.sort;this.$<HTMLInputElement>('#capability-search').value=this.libraryQuery;}
  private restoreScroll():void{const asset=this.scroller(),library=this.scroller('library');if(asset)asset.scrollTop=this.assetScroll;if(library)library.scrollTop=this.libraryScroll;}

  constructor(private readonly root:HTMLElement,private readonly api:WorkbenchAPI,profile:Profile,profiles:Profile[],panel:WorkbenchPanel,private readonly options:ViewOptions){
    this.profile=profile;this.profiles=profiles;this.panel=panel;root.innerHTML=template;
    root.querySelectorAll<HTMLElement>('[data-icon]').forEach(e=>e.innerHTML=icon(e.dataset.icon!));
    root.addEventListener('click',this.click);root.addEventListener('keydown',this.keydown);root.addEventListener('dblclick',this.doubleClick);root.addEventListener('contextmenu',this.contextMenu);root.addEventListener('dragover',this.dragover);root.addEventListener('dragleave',this.dragleave);root.addEventListener('drop',this.drop);
    this.$<HTMLInputElement>('#asset-search').addEventListener('input',e=>{this.query=(e.target as HTMLInputElement).value;this.renderAssets();});
    this.$<HTMLSelectElement>('#asset-sort').addEventListener('change',e=>{this.sort=(e.target as HTMLSelectElement).value;this.renderAssets();});
    this.$<HTMLInputElement>('#capability-search').addEventListener('input',e=>{this.libraryQuery=(e.target as HTMLInputElement).value;this.renderLibrary();});
    this.$('#favorites-only').addEventListener('click',()=>{this.favoritesOnly=!this.favoritesOnly;this.renderLibrary();});
    root.querySelectorAll('.aw-sidebar-bottom').forEach(e=>{const account=document.createElement('div');account.className='aw-sidebar-account';while(e.firstChild)account.append(e.firstChild);e.append(account);e.insertAdjacentHTML('afterbegin','<div class="aw-task-summary" data-task-summary></div><button class="aw-sidebar-action" data-action="handoff-history">'+icon('document')+'任务记录</button>');});
    this.unsubscribeJobs=api.subscribeHandoffs(()=>void this.refreshJobs());
    this.unsubscribe=api.subscribe(id=>{if(id===this.profile.id)void this.reload();});
    this.restoreView(profile);this.narrow.addEventListener('change',this.syncDetails);for(const event of ['input','change','click','scroll'])root.addEventListener(event,this.scheduleView,true);
    this.update(profile,profiles,panel);void this.refreshJobs();void this.reload();void this.loadCatalog();
  }
  private $<T extends HTMLElement=HTMLElement>(selector:string):T { const node=this.root.querySelector<T>(selectors(selector));if(!node)throw new Error('工作台界面不完整：'+selector);return node; }
  private html(selector:string,value:string):void {
    const node=this.$(selector),detail=selector==='#asset-detail'||selector==='#capability-detail',identity=this.profile.id+'/'+(selector==='#asset-detail'?this.selected:this.librarySelected),scroll=detail&&this.detailIdentities.get(selector)===identity?node.querySelector('.aw-detail-body')?.scrollTop||0:0;
    node.innerHTML=value.replace(/class="([^"]*)"/g,(_all,names:string)=>`class="${classes(names)}"`);
    if(detail){this.structureDetail(node);this.detailIdentities.set(selector,identity);node.querySelector('.aw-detail-body')!.scrollTop=scroll;}
  }
  private notify(error:unknown):void { this.options.notice(typeof error==='string'?error:message(error)); }
  update(profile:Profile,profiles:Profile[],panel:WorkbenchPanel):void {
    this.closeAssetMenu();
    const changed=profile.id!==this.profile.id;const oldPanel=this.panel;if(changed||oldPanel!==panel)void this.flushView().catch(error=>this.notify(error));this.profile=profile;this.profiles=profiles;this.panel=panel;
    if(changed){this.generation++;this.previewGeneration++;this.assets=[];this.selected=undefined;this.checked.clear();this.closeModal();this.restoreView(profile);this.jobs=[];void this.refreshJobs();this.$<HTMLInputElement>('#asset-search').value=this.query;void this.reload();}
    if(oldPanel!==panel){this.closeModal();if(oldPanel==='assets'){this.previewGeneration++;this.detailSignature='';this.$('#asset-grid').innerHTML='';this.$('#asset-detail').innerHTML='';}}
    this.$('#workbench').hidden=panel!=='assets';this.$('#capability-page').hidden=panel==='assets';
    this.$('#sidebar-account').textContent=profile.name+'的本机文件';
    if(panel==='assets'){if(changed||oldPanel!==panel||!this.loaded)this.renderAssets();}
    else { this.selectLibrary(panel); }
    if(changed||oldPanel!==panel){clearTimeout(this.detailTimer);this.detailOpen=false;}this.syncDetails();this.restoreScroll();
  }
  private async reload():Promise<void>{
    const ticket=++this.generation,profile=this.profile.id;
    try{const assets=await this.api.assets(profile);if(this.disposed||ticket!==this.generation||profile!==this.profile.id)return;this.assets=assets;this.loaded=true;if(this.panel==='assets'){this.renderAssets();this.restoreScroll();const preview=this.assets.find(a=>a.id===this.restoringPreview&&a.status==='ready'&&!a.trashedAt);this.restoringPreview=undefined;if(preview)void this.preview(preview);}}
    catch(error){if(this.disposed||ticket!==this.generation||profile!==this.profile.id)return;this.html('#asset-grid',`<div class="error-state"><h3>资产库暂时无法读取</h3><p>${esc(message(error))}</p><button class="secondary" data-action="reload-assets">重试</button><button class="secondary" data-action="asset-folder">打开资产目录</button></div>`);this.html('#asset-detail','<div class="empty-detail">文件与索引会保留，不会自动清空。</div>');}
  }
  private async loadCatalog():Promise<void>{try{const result=await this.api.capabilities();if(this.disposed)return;this.catalog=result;this.capabilityFavorites=new Set(result.favorites||[]);for(const kind of ['skills','agents'] as const)for(const button of this.root.querySelectorAll(`[data-action="${kind}"]`)){const count=button.querySelector('span');if(count)count.textContent=String(result[kind].length);}this.renderLibrary();}catch(error){if(!this.disposed)this.html('#capability-grid',`<div class="error-state"><h3>能力库暂时无法读取</h3><p>${esc(message(error))}</p><button class="secondary" data-action="reload-catalog">重试</button></div>`);}}
  private matches(asset:AssetItem,filter=this.filter):boolean {if(filter==='trash')return asset.trashedAt!==undefined;if(asset.trashedAt!==undefined)return false;if(filter==='all')return true;if(filter==='favorite')return asset.favorite;if(filter==='converted')return asset.source.kind==='conversion';return asset.kind===filter;}
  private visible():AssetItem[]{return this.assets.filter(a=>this.matches(a)&&`${a.name} ${a.tags.join(' ')} ${a.source.title||''}`.toLowerCase().includes(this.query.toLowerCase())).sort((a,b)=>this.sort==='name'?a.name.localeCompare(b.name,'zh-CN'):this.sort==='size'?b.size-a.size:b.createdAt-a.createdAt);}
  private current():AssetItem|undefined{return this.assets.find(a=>a.id===this.selected);}
  private thumb(asset:AssetItem):string {if(asset.thumbnailURL)return `<img loading="lazy" decoding="async" src="${esc(asset.thumbnailURL)}" alt="${esc(asset.name)}">`;return `${icon(asset.kind==='web'?'web':asset.kind==='data'?'code':'document')}<span class="derived-marker">${esc(asset.status==='ready'?asset.fileName.split('.').at(-1)?.toUpperCase():statusName[asset.status])}</span>`;}
  private card(asset:AssetItem):string{return `<article class="asset-card ${asset.id===this.selected?'selected':''} ${this.checked.has(asset.id)?'checked':''}" tabindex="0" role="button" data-asset="${asset.id}" data-status="${asset.status}" aria-label="查看 ${esc(asset.name)}"><button class="card-check" data-check="${asset.id}" aria-label="选择 ${esc(asset.name)}" aria-pressed="${this.checked.has(asset.id)}">${this.checked.has(asset.id)?icon('check'):''}</button><button class="card-star ${asset.favorite?'is-favorite':''}" data-favorite="${asset.id}" aria-label="${asset.favorite?'取消收藏':'收藏'} ${esc(asset.name)}">${icon('star')}</button><div class="asset-thumb">${this.thumb(asset)}${asset.source.fromAssetId?'<span class="derived-marker">转换副本</span>':''}</div><div class="asset-card-info"><div class="asset-card-name">${esc(asset.name)}</div><div class="file-meta"><span class="type-label">${esc(asset.fileName.split('.').at(-1)?.toUpperCase())}</span><span>${size(asset.status==='ready'?asset.size:asset.receivedBytes)}</span><span class="file-time">${esc(asset.status==='ready'?new Date(asset.createdAt).toLocaleDateString('zh-CN'):statusName[asset.status])}</span></div></div></article>`;}
  private renderAssets():void {
    const visible=this.visible();if(!visible.some(a=>a.id===this.selected)){this.selected=visible[0]?.id;this.explicitSelection=false;}
    this.html('#asset-navigation',filters.map(f=>`<button class="nav-item ${this.filter===f.id?'active':''}" data-filter="${f.id}"><span class="inline-icon">${icon(f.icon)}</span>${f.name}<span>${this.assets.filter(a=>this.matches(a,f.id)).length}</span></button>`).join(''));
    this.html('#asset-title',`${filters.find(f=>f.id===this.filter)!.name} <span>${visible.length}</span>`);
    this.html('.asset-main .breadcrumb',`${esc(this.profile.name)} <span>/</span> 资产`);
    this.html('#asset-context',`<span class="source-scope"><span class="inline-icon">${icon('shield')}</span>仅显示${esc(this.profile.name)}的资产</span><p>本机保存 · 原件与转换副本分开</p>`);
    this.$('#asset-grid').className=classes(`asset-grid ${this.layout==='list'?'list-mode':''}`);
    this.html('#asset-grid',visible.length?visible.map(a=>this.card(a)).join(''):`<div class="empty-state">${icon(this.query?'search':'folder')}<h3>${this.query?'没有找到匹配的资产':this.filter==='trash'?'回收站是空的':'这里还没有资产'}</h3><p>${this.query?'试试文件名、标签或来源对话。':'在 App 中下载，或导入你已有的文件。'}</p><button class="secondary" data-action="${this.query?'clear-search':'import'}">${this.query?'清除搜索':'导入文件'}</button></div>`);
    this.checked=new Set([...this.checked].filter(id=>visible.some(a=>a.id===id)));
    this.$('#selection-bar').hidden=!this.checked.size;
    this.html('#selection-bar',`<strong>已选择 ${this.checked.size} 项</strong><button data-action="send-selected">交给 GPT</button><button data-action="convert-selected">转换格式</button><button data-action="trash-selected">${this.filter==='trash'?'恢复':'移入回收站'}</button><button data-action="clear-selection">取消选择</button>`);
    this.html('#asset-status',`<span>${visible.length} 个资产 · ${esc(this.profile.name)}</span><span>保存在本机资产目录</span>`);
    this.root.querySelectorAll<HTMLElement>('[data-view]').forEach(e=>e.classList.toggle('aw-active',e.dataset.view===this.layout));this.renderDetail();
  }
  private renderDetail():void {
    const signature=JSON.stringify([this.profile.name,this.current()]);if(signature===this.detailSignature)return;this.detailSignature=signature;
    const asset=this.current(),ticket=++this.previewGeneration;
    if(!asset){this.html('#asset-detail',`<div class="empty-detail">${icon('document')}<p>选择一个文件<br>查看内容、来源与可用操作</p></div>`);return;}
    const parent=this.assets.find(a=>a.id===asset.source.fromAssetId),ready=asset.status==='ready'&&!asset.trashedAt;
    this.html('#asset-detail',`<div class="detail-topline"><span>文件详情</span><button class="icon-button" data-action="preview" aria-label="展开预览" ${ready?'':'disabled'}>${icon('expand')}</button></div><div class="detail-preview" id="wb-detail-content" tabindex="0" role="button" data-action="preview" aria-label="预览 ${esc(asset.name)}">${this.thumb(asset)}</div><h3 class="detail-name">${esc(asset.name)}</h3><div class="detail-tags">${asset.tags.map(t=>`<span class="tag">${esc(t)}</span>`).join('')}</div>${asset.trashedAt?'<button class="primary" data-action="restore-current">恢复这个文件</button>':`<div class="detail-primary-actions"><button class="primary" data-action="send-current" ${ready?'':'disabled'}><span class="inline-icon">${icon('sparkles')}</span>交给 GPT</button><button class="secondary" data-action="convert-current" ${ready?'':'disabled'}><span class="inline-icon">${icon('convert')}</span>转换格式</button></div>`}<div class="detail-properties"><div class="property-row"><span>状态</span><span>${esc(statusName[asset.status])}</span></div><div class="property-row"><span>格式</span><span>${esc(asset.mime)}</span></div><div class="property-row"><span>大小</span><span>${size(asset.status==='ready'?asset.size:asset.receivedBytes)}${asset.totalBytes&&asset.status!=='ready'?' / '+size(asset.totalBytes):''}</span></div><div class="property-row"><span>所属账号</span><span>${esc(this.profile.name)}</span></div><div class="property-row"><span>来源</span><span>${asset.source.conversationURL?`<button class="source-link" data-action="source">${esc(asset.source.title||'ChatGPT 对话')} ↗</button>`:esc(asset.source.kind==='import'?'手动导入':asset.source.title||'网页下载')}</span></div><div class="property-row"><span>添加时间</span><span>${new Date(asset.createdAt).toLocaleString('zh-CN')}</span></div>${parent?`<div class="property-row"><span>原件</span><span><button class="source-link" data-parent="${parent.id}">${esc(parent.name)}</button></span></div>`:''}</div>${asset.error?`<p class="stage-notice">${esc(asset.error)}</p>`:''}<div class="detail-section-title">整理标记<button data-action="edit-tags">编辑</button></div><div class="detail-tags">${asset.tags.map(t=>`<span class="tag">${esc(t)}</span>`).join('')||'<span class="tag">尚未添加标签</span>'}</div>${['downloading','paused','interrupted'].includes(asset.status)?`<div class="detail-small-actions"><button data-action="download-${asset.status==='downloading'?'pause':'resume'}">${asset.status==='downloading'?'暂停':'继续'}</button><button data-action="download-cancel">取消下载</button></div>`:''}<div class="detail-small-actions"><button data-action="rename">${icon('edit')}重命名</button><button data-action="reveal">${icon('folder')}${navigator.platform.startsWith('Win')?'在资源管理器显示':'在访达显示'}</button><button data-action="trash-current">${icon('trash')}${asset.trashedAt?'恢复':'回收站'}</button></div><div class="detail-small-actions"><button data-action="export">${icon('download')}另存为</button></div>`);
    if(asset.source.kind==='conversion'&&asset.status==='processing'){const cancel=document.createElement('button');cancel.className=classes('secondary');cancel.dataset.action='cancel-conversion';cancel.textContent='取消转换';this.$('#asset-detail .detail-body').append(cancel);}
    if(ready&&asset.kind==='web')void this.api.preview(this.profile.id,asset.id).then(preview=>{if(this.disposed||ticket!==this.previewGeneration||!preview.url)return;this.html('#detail-content',`<iframe credentialless sandbox="allow-scripts allow-same-origin" title="${esc(asset.name)}" src="${esc(preview.url)}"></iframe>`);}).catch(()=>{});
  }
  private modal(title:string,description:string,content:string,footer=''):void {
    this.modalVersion++;
    this.lastFocus=document.activeElement instanceof HTMLElement?document.activeElement:undefined;
    this.html('#modal-root',`<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="modal-header"><div><h2>${esc(title)}</h2><p>${esc(description)}</p></div><button class="icon-button" data-action="close-modal" aria-label="关闭对话框">${icon('close')}</button></div><div class="modal-content">${content}</div>${footer?`<div class="modal-footer">${footer}</div>`:''}</section></div>`);
    requestAnimationFrame(()=>{this.root.querySelector<HTMLElement>('.aw-modal [autofocus],.aw-modal input,.aw-modal select,.aw-modal button')?.focus();});
  }
  private closeModal():void {this.jobView=undefined;this.currentJob=undefined;this.modalVersion++;this.$('#modal-root').innerHTML='';this.previewAsset=undefined;if(this.lastFocus?.isConnected)this.lastFocus.focus();}
  private async preview(asset=this.current(),source=false,online=false):Promise<void>{
    if(!asset||asset.status!=='ready')return;const profile=this.profile.id,request=++this.modalVersion;
    try{const data=await this.api.preview(profile,asset.id,{online});if(this.disposed||profile!==this.profile.id||request!==this.modalVersion)return;this.previewAsset=asset;
      const sourceTab=['web','data'].includes(asset.kind)||/\.(md|txt|svg)$/i.test(asset.fileName);
      this.modal(asset.name,`${this.profile.name} · ${size(asset.size)}`,`${sourceTab?`<div class="viewer-tabs"><button data-action="preview-render" class="${source?'':'active'}">预览</button><button data-action="preview-source" class="${source?'active':''}">源文件</button></div>`:''}${data.mode==='html'?`<div class="viewer-network"><span>${data.online?'联网预览：允许此文件加载 HTTPS 资源':'离线预览：联网资源默认关闭'}</span><button class="secondary" data-action="${data.online?'preview-render':'preview-online'}">${data.online?'返回离线预览':'允许此文件联网'}</button></div>${data.warning?`<p class="stage-notice" role="status">${esc(data.warning)}</p>`:''}`:''}<div class="preview-viewer" id="wb-viewer"></div><p class="viewer-caption">原件保留。HTML 使用临时网页空间，不共享账号登录；联网模式下，文件可以向外部服务发送请求。浏览器不支持的格式可交给系统应用。</p>`,'<button class="secondary" data-action="open-external">使用系统应用打开</button><button class="secondary" data-action="export">另存为</button><button class="primary" data-action="send-current">交给 GPT</button>');
      this.$('.modal').classList.add('aw-preview-modal');
      if(source){const version=this.modalVersion,text=await this.api.readText(profile,asset.id);if(!this.disposed&&profile===this.profile.id&&version===this.modalVersion)this.html('#viewer',`<pre>${esc(text)}</pre>`);return;}
      await this.renderPreview(data,asset);
    }catch(error){if(!this.disposed&&profile===this.profile.id){if(this.root.querySelector('#wb-viewer'))this.html('#viewer',`<div class="empty-detail"><p>${esc(message(error))}</p><button class="secondary" data-action="open-external">使用系统应用打开</button></div>`);this.notify(error);}}
  }
  private async renderPreview(data:AssetPreview,asset:AssetItem):Promise<void>{
    const viewer=this.$('#viewer'),profile=this.profile.id,version=this.modalVersion;const current=()=>!this.disposed&&viewer.isConnected&&this.profile.id===profile&&this.modalVersion===version;
    if(data.mode==='image'){this.html('#viewer',`<img src="${esc(data.url)}" alt="${esc(asset.name)}">`);viewer.querySelector('img')?.addEventListener('error',()=>{if(current())this.html('#viewer','<div class="empty-detail">当前浏览器不能解码此图片。<p><button class="secondary" data-action="open-external">使用系统应用打开</button></p></div>');});return;}
    if(data.mode==='html'){this.html('#viewer',`<iframe credentialless sandbox="allow-scripts allow-same-origin" title="${esc(asset.name)}" src="${esc(data.url)}"></iframe>`);return;}
    if(data.mode==='audio'||data.mode==='video'){const tag=data.mode==='audio'?'audio':'video';this.html('#viewer',`<${tag} controls preload="metadata" src="${esc(data.url)}" aria-label="${esc(asset.name)}"></${tag}><p class="viewer-caption" id="wb-media-status">能否播放取决于文件编码；也可使用系统应用打开。</p>`);viewer.querySelector('audio,video')?.addEventListener('error',()=>{if(current())this.$('#media-status').textContent='当前浏览器不能播放此编码，请使用系统应用打开或另存为。';});return;}
    if(data.mode==='pdf'){this.html('#viewer','<div class="empty-detail">正在读取 PDF…</div>');const {mountPDF}=await import('./workbench-pdf');if(!current())return;await mountPDF(viewer,data.url!);return;}
    if(data.mode==='markdown'){const {markdownHTML}=await import('../shared/formats');if(!current())return;this.html('#viewer','<iframe sandbox="" title="Markdown 阅读视图"></iframe>');this.$('#viewer').querySelector('iframe')!.srcdoc=markdownHTML(data.text||'');return;}
    if(data.mode==='table'){const {csvRows}=await import('../shared/formats');if(!current())return;try{const rows=csvRows(data.text||'');this.html('#viewer','<table>'+rows.slice(0,501).map((row,i)=>'<tr>'+row.map(cell=>`<${i?'td':'th'}>${esc(cell)}</${i?'td':'th'}>`).join('')+'</tr>').join('')+'</table>');if(rows.length>501)this.notify('表格预览显示前 500 行，原文件完整保留。');}catch{this.html('#viewer',`<pre>${esc(data.text)}</pre>`);this.notify('CSV 结构不一致，已显示原文。');}return;}
    if(data.mode==='unsupported'){this.html('#viewer',`<div class="empty-detail">${data.warning?`<p>${esc(data.warning)}</p>`:'此格式使用系统应用查看。'}<br>可打开原文件、另存为或交给 GPT。<p><button class="secondary" data-action="open-external">使用系统应用打开</button></p></div>`);return;}
    this.html('#viewer',`<pre>${esc(data.text)}</pre>`);
  }
  private async act(action:AssetAction):Promise<void>{const profile=this.profile.id;await this.api.act(profile,action);if(profile===this.profile.id)await this.reload();}
  private async edit(tags=false):Promise<void>{const asset=this.current();if(!asset)return;this.modal(tags?'编辑标签':'重命名文件','原件与来源记录保留。',`<label class="field-label">${tags?'标签（逗号分隔）':'文件名称'}</label><input class="field-input" id="wb-edit-value" autofocus maxlength="240" value="${esc(tags?asset.tags.join('，'):asset.name)}">`,'<button class="secondary" data-action="close-modal">取消</button><button class="primary" data-action="save-edit">保存</button>');this.$('#edit-value').dataset.tags=String(tags);this.$('#edit-value').dataset.asset=asset.id;}
  private items():(SkillDefinition|AgentSummary)[]{return this.catalog?(this.libraryKind==='skills'?this.catalog.skills:this.catalog.agents):[];}
  private selectLibrary(kind:'skills'|'agents'):void{
    if(this.libraryKind!==kind){this.libraryFilter='all';this.libraryQuery='';this.librarySelected=undefined;this.$<HTMLInputElement>('#capability-search').value='';}
    this.libraryKind=kind;this.renderLibrary();
  }
  private renderLibrary():void {
    if(!this.catalog)return;const agents=this.libraryKind==='agents',all=this.items();
    const visible=all.filter(i=>(this.libraryFilter==='all'||this.libraryFilter==='internet'&&i.internetPriority||this.libraryFilter===i.category)&&(!this.favoritesOnly||this.capabilityFavorites.has(i.id))&&`${i.name} ${i.category} ${i.description}`.toLowerCase().includes(this.libraryQuery.toLowerCase())).sort((a,b)=>Number(b.internetPriority)-Number(a.internetPriority)||Number(this.capabilityFavorites.has(b.id))-Number(this.capabilityFavorites.has(a.id)));
    if(!visible.some(i=>i.id===this.librarySelected))this.librarySelected=visible[0]?.id;
    this.$('#skills-nav').classList.toggle('aw-active',!agents);this.$('#agents-nav').classList.toggle('aw-active',agents);
    this.html('#capability-title',`${agents?'智能体':'Skill'} <span>${all.length}</span>`);
    this.$('#capability-description').textContent=agents?'选择专业角色，与任务和文件一起进入新的对话。':'每个任务都带有输入、处理步骤、交付结构和质量检查。';
    this.html('#library-notice',`<span class="inline-icon">${icon(agents?'agents':'sparkles')}</span><span>${agents?`${all.length} 个自有专业角色，互联网相关优先；可单独使用或搭配 Skill。`:`${all.length} 个独立任务，完整定义随 App 保存；文件处理使用所选账号的 ChatGPT 网页。`}</span>`);
    const cats=[...new Set(all.map(i=>i.category))];
    this.html('#capability-navigation',[{id:'all',name:'全部'+(agents?'角色':'Skill'),count:all.length},...(agents?[{id:'internet',name:'互联网相关',count:all.filter(i=>i.internetPriority).length}]:[]),...cats.map(c=>({id:c,name:c,count:all.filter(i=>i.category===c).length}))].map(c=>`<button class="nav-item ${this.libraryFilter===c.id?'active':''}" data-library-filter="${esc(c.id)}">${esc(c.name)}<span>${c.count}</span></button>`).join(''));
    this.html('#library-result-heading',`<span>${visible.length} 个${agents?'角色':'任务'}</span><span>${agents?'互联网相关优先':'按输入文件与目标选择'}</span>`);
    this.$('#favorites-only').classList.toggle('aw-active',this.favoritesOnly);
    let internet=true;
    this.html('#capability-grid',visible.length?visible.map((item,index)=>{const divider=agents&&index>0&&internet&&!item.internetPriority?'<div class="section-break">通用与其他专业领域</div>':'';internet=item.internetPriority;return divider+`<article class="capability-card ${item.id===this.librarySelected?'selected':''}" tabindex="0" role="button" data-capability="${item.id}" data-priority="${item.internetPriority}" aria-label="查看 ${esc(item.name)}"><div class="capability-card-header"><span class="capability-symbol">${icon(agents?'agents':'sparkles')}</span><button class="card-star ${this.capabilityFavorites.has(item.id)?'is-favorite':''}" data-capability-favorite="${item.id}" aria-label="收藏 ${esc(item.name)}">${icon('star')}</button></div><h3>${esc(item.name)}</h3><p>${esc(item.description)}</p><div class="capability-card-foot"><span class="tag">${esc(item.category)}</span>${agents&&item.internetPriority?'<span class="internet-tag">互联网相关</span>':''}</div></article>`;}).join(''):(agents&&!all.length?'<div class="empty-state"><h3>暂无预置智能体</h3><p>可继续使用 Skill 或自定义处理要求。</p></div>':'<div class="empty-state"><h3>没有匹配的能力</h3><p>换一个名称、用途或关键词试试。</p><button class="secondary" data-action="clear-library-search">清除筛选</button></div>'));
    const item=all.find(i=>i.id===this.librarySelected);
    if(!item){this.html('#capability-detail','<div class="empty-detail">选择一个任务或角色，查看完整要求。</div>');return;}
    const skill='instructions'in item?item:undefined;
    this.html('#capability-detail',`<div class="detail-topline"><span>${agents?'智能体':'Skill'}详情</span><span>${agents?'角色定义':'完整任务定义'}</span></div><div class="capability-symbol">${icon(agents?'agents':'sparkles')}</div><h3>${esc(item.name)}</h3><div class="detail-tags"><span class="tag">${esc(item.category)}</span>${item.internetPriority?'<span class="tag">互联网相关</span>':''}</div><p class="capability-description">${esc(item.description)}</p><button class="primary" data-action="use-capability">${icon('sparkles')}使用${agents?'此角色':'此 Skill'}发起任务</button>${skill?`<div class="detail-field-label">适用文件</div><div>${skill.inputs.map(s=>`<span class="tag">${esc(s)}</span>`).join('')}</div><div class="detail-field-label">处理步骤</div><ol>${skill.steps.map(s=>`<li>${esc(s)}</li>`).join('')}</ol><div class="detail-field-label">交付结构</div><ul>${skill.outputSections.map(s=>`<li>${esc(s)}</li>`).join('')}</ul><div class="detail-field-label">质量检查</div><ul>${skill.checks.map(s=>`<li>${esc(s)}</li>`).join('')}</ul><div class="detail-field-label">示例</div><p class="capability-description">${esc(skill.examples[0].request)}</p>`:`<div class="detail-field-label">在对话中使用</div><ol><li>选择文件和处理任务</li><li>带入角色的工作方法与检查要求</li><li>使用当前会话实际具备的工具</li></ol><p class="capability-description">角色提供分析视角和工作方法，不会自动获得外部工具、额外文件或其他账号的权限。</p><button class="secondary" data-action="agent-full">查看角色全文</button>`}`);
  }
  private taskFiles:AssetItem[]=[];
  private openTask(files:AssetItem[]):void {
    if(!this.catalog){this.notify('能力库正在加载，请稍后重试');return;}
    this.taskRequestId=crypto.randomUUID();this.jobView=undefined;this.currentJob=undefined;
    this.taskFiles=files.filter(a=>a.status==='ready'&&!a.trashedAt);
    if(!this.taskSkill&&!this.taskAgent){const name=this.taskFiles[0]?.kind==='web'?'HTML 结构解读':this.taskFiles[0]?.kind==='image'?'截图问题分析':'文档摘要';this.taskSkill=this.catalog.skills.find(s=>s.name===name)?.id;}
    const options=(items:(SkillDefinition|AgentSummary)[],id?:string)=>items.map(i=>`<option value="${i.id}" ${i.id===id?'selected':''}>${esc(i.name)}</option>`).join('');
    this.modal('交给 GPT','选择文件、任务、角色与目标账号。',`<div class="file-chips">${this.taskFiles.length?this.taskFiles.map(a=>`<div class="file-chip">${icon(a.kind==='image'?'image':'document')}${esc(a.name)}<small>${size(a.size)}</small></div>`).join(''):'<div class="file-chip">本次不附带文件</div>'}</div><div class="field-row"><div><label class="field-label">使用账号</label><select id="wb-task-account" class="field-select">${this.profiles.map(p=>`<option value="${p.id}" ${p.id===this.profile.id?'selected':''}>${esc(p.name)}</option>`).join('')}</select></div><div><label class="field-label">输出语言</label><select id="wb-task-language" class="field-select"><option>简体中文</option><option>English</option><option>跟随文件语言</option></select></div></div><div id="wb-cross-account"></div><label class="field-label">处理任务 · Skill</label><select class="field-select" id="wb-task-skill"><option value="">不指定 Skill</option>${options(this.catalog.skills,this.taskSkill)}</select><label class="field-label">专业角色 · 选填</label><select class="field-select" id="wb-task-agent"><option value="">使用 ChatGPT 默认角色</option>${options(this.catalog.agents,this.taskAgent)}</select><label class="field-label">处理要求</label><textarea class="field-textarea" id="wb-task-instructions" placeholder="例如：保留风格，修复移动端排版，提供修改后的文件。"></textarea><div class="modal-note">${icon('shield')}<span>将在所选账号中新建对话、上传附件并发送一次。HTML、SVG 与代码文件会使用 .txt 附件名保留原始内容。发送不确定时不会自动重试。</span></div><div id="wb-prompt-preview"></div>`,'<button class="secondary" data-action="task-prompt">查看处理指令</button><button class="primary" data-action="start-handoff">新建对话并发送</button>');
    this.$<HTMLSelectElement>('#task-account').addEventListener('change',()=>{this.html('#cross-account',this.$<HTMLSelectElement>('#task-account').value!==this.profile.id&&this.taskFiles.length?'<div class="cross-account-warning">文件将交给另一个账号处理。<label><input id="wb-cross-consent" type="checkbox">我确认使用所选账号处理这些文件</label></div>':'');});
  }
  private async refreshJobs():Promise<void>{
    const profile=this.profile.id;try{const jobs=await this.api.handoffs(profile);if(this.disposed||this.profile.id!==profile)return;this.jobs=jobs;this.renderTaskSummary();if(this.jobView==='list')this.showJobs();else if(this.jobView==='detail'&&this.currentJob)this.showJob(this.currentJob);}catch(error){if(this.jobView)this.notify(error);}
  }
  private showJobs():void{
    this.jobView='list';this.modal('任务记录','本机保存；发送结果不确定的任务不会自动重发。',this.jobs.length?this.jobs.map(j=>`<button class="file-chip" data-job="${j.id}"><strong>${esc(j.label)}</strong><span>${esc(j.sourceProfileName)} → ${esc(j.targetProfileName)}</span><small>${esc(handoffStatusNames[j.status])} · ${new Date(j.createdAt).toLocaleString()}</small></button>`).join(''):'<p class="stage-notice">暂无任务。选择资产或 Skill，即可发起网页对话。</p>','<button class="secondary" data-action="close-modal">关闭</button>');
  }
  private renderTaskSummary():void{
    const job=this.jobs.find(j=>['preparing','opening','uploading','drafting','sending'].includes(j.status))||this.jobs[0];
    this.root.querySelectorAll<HTMLElement>('[data-task-summary]').forEach(node=>{node.innerHTML=job?`<button data-summary-job="${job.id}"><strong>${esc(handoffStatusNames[job.status])}</strong><span>${job.progress?.length?`第 ${handoffStages.indexOf(job.progress.at(-1)!.stage)+1}/7 步 · `:''}${esc(job.label)}</span></button>`:'';});
  }
  private showJob(id:string):void{
    const j=this.jobs.find(j=>j.id===id);if(!j)return;this.jobView='detail';this.currentJob=id;
    const active=['preparing','opening','uploading','drafting','sending'].includes(j.status),last=j.progress?.at(-1);
    const stopped=['failed','cancelled','interrupted','uncertain'].includes(j.status);
    const steps=handoffStages.map((stage,index)=>{const p=j.progress?.find(p=>p.stage===stage),state=p?.completedAt?'done':p?(stopped?'stopped':'current'):'waiting';return `<li data-stage="${stage}" data-state="${state}" ${state==='current'?'aria-current="step"':''}><span class="step-number">${state==='done'?'✓':index+1}</span><div><strong>${handoffStageNames[stage]}</strong><small>${p?new Date(p.startedAt).toLocaleTimeString('zh-CN',{hour12:false}): '等待前序步骤'}${p?.note?' · '+esc(p.note):''}</small></div><span>${state==='done'?'已完成':state==='stopped'?handoffStatusNames[j.status]:state==='current'?'进行中':''}</span></li>`;}).join('');
    this.modal(j.label,`${j.sourceProfileName} → ${j.targetProfileName} · ${handoffStatusNames[j.status]}`,`<p class="stage-notice" role="status">${stopped&&last?`停在第 ${handoffStages.indexOf(last.stage)+1}/7 步：${handoffStageNames[last.stage]}。<br>`:''}${esc(j.message)}</p><ol class="handoff-progress" aria-label="任务七阶段进度">${steps}</ol>${!j.progress?'<p class="task-source-hint">旧任务未记录详细阶段，保留原有结果。</p>':''}<div class="file-chips">${j.files.map(f=>`<div class="file-chip">${icon('document')}${esc(f.name)}<small>${esc(f.uploadName)} · ${size(f.size)}</small></div>`).join('')}</div>${j.status==='uncertain'?'<p class="cross-account-warning">请先检查目标对话是否已发送。不要直接新建同一任务，以免重复发送。</p>':''}<details><summary>查看任务要求</summary><pre class="source-text">${esc(j.request.instructions||'使用选定 Skill / 角色的任务定义。')}</pre></details>`,'<button class="secondary" data-action="handoff-history">全部记录</button><button class="secondary" data-action="copy-handoff">复制完整指令</button>'+(active&&j.status!=='sending'?'<button class="secondary" data-action="cancel-handoff">取消任务</button>':'')+(j.tabId?'<button class="primary" data-action="open-handoff">打开目标对话</button>':''));
  }
  private async startHandoff():Promise<void>{
    if(this.submitting)return;const profile=this.profile.id;
    const request:HandoffRequest={requestId:this.taskRequestId,sourceProfileId:profile,targetProfileId:this.$<HTMLSelectElement>('#task-account').value,assetIds:this.taskFiles.map(f=>f.id),crossAccountConfirmed:!!this.root.querySelector<HTMLInputElement>('#wb-cross-consent')?.checked,skillId:this.$<HTMLSelectElement>('#task-skill').value||undefined,agentId:this.$<HTMLSelectElement>('#task-agent').value||undefined,language:this.$<HTMLSelectElement>('#task-language').value,instructions:this.$<HTMLTextAreaElement>('#task-instructions').value};
    if(request.sourceProfileId!==request.targetProfileId&&request.assetIds.length&&!request.crossAccountConfirmed)throw Error('请确认使用另一个账号处理这些文件。');
    this.submitting=true;this.modal('正在创建任务','来源和目标账号已固定。','<p class="stage-notice">正在校验文件，原件保留。</p>');const ticket=this.modalVersion;
    try{const job=await this.api.startHandoff(request);if(this.disposed||this.profile.id!==profile||ticket!==this.modalVersion)return;this.jobs=[job,...this.jobs.filter(j=>j.id!==job.id)];this.showJob(job.id);await this.refreshJobs();}catch(error){if(!this.disposed&&profile===this.profile.id&&ticket===this.modalVersion)this.modal('任务未能创建','尚未发送消息。',`<p class="stage-notice">${esc(message(error))}</p>`,'<button class="secondary" data-action="close-modal">关闭</button>');throw error;}finally{this.submitting=false;}
  }
  private selectedFiles():AssetItem[]{return this.checked.size?this.assets.filter(a=>this.checked.has(a.id)):this.current()?[this.current()!]:[];}
  private convertFiles:AssetItem[]=[];
  private convertFormat:ConversionOptions['format']='PNG';
  private openConvert(files:AssetItem[]):void {
    const ready=files.filter(a=>a.status==='ready'&&!a.trashedAt);if(!ready.length)return;
    const choices=conversionFormats(ready[0]).filter(f=>ready.every(a=>conversionFormats(a).includes(f)));
    if(!choices.length){this.notify('这些文件没有共同的本地转换格式，请按类型分别选择。');return;}
    this.convertFiles=ready;this.convertFormat=choices[0];
    const pdf=ready.some(a=>a.fileName.toLowerCase().endsWith('.pdf')),image=ready.some(a=>a.kind==='image'),web=ready.some(a=>a.kind==='web');
    this.modal('转换格式','本地处理，保存新副本并保留原件与来源。',`<div class="file-chips">${ready.map(a=>`<div class="file-chip">${icon(a.kind==='image'?'image':'document')}${esc(a.name)}<small>${size(a.size)}</small></div>`).join('')}</div><label class="field-label">转换为</label><div class="format-options">${choices.map((f,i)=>`<button class="format-option ${i?'':'active'}" data-format="${f}">${f}<small>${['PNG','JPEG','WEBP'].includes(f)?'图像副本':f==='PDF'?'文档副本':'数据或网页副本'}</small></button>`).join('')}</div><label class="field-label">输出名称${ready.length>1?'（批量时沿用各原件名称）':''}</label><input class="field-input" id="wb-convert-name" maxlength="160" value="${esc(ready[0].name.replace(/\.[^.]+$/,''))}" ${ready.length>1?'disabled':''}>${image||pdf||web?'<div class="field-row"><div><label class="field-label">输出宽度 · 像素</label><input class="field-input" id="wb-convert-width" type="number" min="16" max="8192" placeholder="保持原始尺寸"></div><div><label class="field-label">图片质量 · 10–100</label><input class="field-input" id="wb-convert-quality" type="number" min="10" max="100" value="92"></div></div>':''}${pdf?'<label class="field-label">PDF 页码</label><input class="field-input" id="wb-convert-pages" value="1" placeholder="例如：1,3-5"><p class="task-source-hint">每页生成一个图片资产，一次最多 20 页。</p>':''}<div class="modal-note">${icon('shield')}<span>转换不会上传到 GPT，也不会覆盖原件。JPEG 使用白色背景；网页仅加载本地与内嵌内容。CSV 首行为字段名，数据保留为文本；导出会转义可能执行的表格公式。</span></div>`,'<button class="secondary" data-action="close-modal">取消</button><button class="primary" data-action="run-conversion">创建转换副本</button>');
  }
  private async runConversion():Promise<void>{
    const profile=this.profile.id,name=this.profile.name,files=this.convertFiles;
    const field=(id:string)=>this.root.querySelector<HTMLInputElement>('#wb-'+id)?.value||'';
    const width=field('convert-width'),quality=field('convert-quality'),pageText=field('convert-pages');
    let pages:number[]|undefined;
    if(pageText){pages=[];for(const part of pageText.split(/[,，]/)){const range=/^\s*(\d+)\s*(?:-\s*(\d+))?\s*$/.exec(part);if(!range)throw new Error('请用 1,3-5 的格式填写 PDF 页码');const start=Number(range[1]),end=Number(range[2]||range[1]);if(start<1||end<start||end-start>20)throw new Error('PDF 页码范围无效');for(let i=start;i<=end;i++)pages.push(i);}pages=[...new Set(pages)];if(pages.length>20)throw new Error('一次最多转换 20 页');}
    const options:ConversionOptions={format:this.convertFormat,...(files.length===1?{name:field('convert-name')}:{}),...(width?{width:Number(width)}:{}),...(quality?{quality:Number(quality)/100}:{}),...(pages?{pages}:{})};
    this.modal('正在本地转换',`${files.length} 个文件 · ${name}`,`<div class="stage-notice">原始文件保留。完成后会在原账号的“最近转换”中显示副本。<br>关闭这个面板会继续处理；可从处理中的资产取消任务。</div>`,'<button class="secondary" data-action="close-modal">关闭面板</button>');
    const version=this.modalVersion;
    let result:Awaited<ReturnType<WorkbenchAPI['convert']>>;
    try{result=await this.api.convert(profile,files.map(a=>a.id),options);}
    catch(error){if(!this.disposed&&this.profile.id===profile&&version===this.modalVersion)this.modal('转换未完成','原始文件保留。',`<p class="stage-notice">${esc(message(error))}</p>`,'<button class="secondary" data-action="close-modal">关闭</button>');throw error;}
    this.notify(`${name}：生成 ${result.assets.length} 个副本${result.failures.length?'，'+result.failures.length+' 个源文件未完成':''}`);
    if(this.disposed||this.profile.id!==profile)return;
    await this.reload();this.filter='converted';this.query='';this.$<HTMLInputElement>('#asset-search').value='';this.selected=result.assets.at(-1)?.id;this.checked.clear();this.renderAssets();
    if(version===this.modalVersion)this.modal('转换结果',`已归档 ${result.assets.length} 个新副本，原件保留。`,`${result.assets.map(a=>`<div class="file-chip">${icon(a.kind==='image'?'image':'document')}${esc(a.name)}<small>${size(a.size)}</small></div>`).join('')}${result.failures.length?'<div class="stage-notice">'+result.failures.map(f=>`${esc(f.name)}：${esc(f.error)}`).join('<br>')+'</div>':''}`,'<button class="primary" data-action="close-modal">查看资产</button>');
  }
  private readonly click=(event:MouseEvent):void=>{void this.handleClick(event).catch(error=>this.notify(error));};
  private async handleClick(event:MouseEvent):Promise<void>{
    if(this.assetMenu&&!this.assetMenu.contains(event.target as Node))this.closeAssetMenu();
    const element=event.target instanceof Element?event.target:undefined;if(!element)return;
    const job=element.closest<HTMLElement>('[data-job],[data-summary-job]');if(job){this.showJob(job.dataset.job||job.dataset.summaryJob!);return;}
    if(element.classList.contains('aw-modal-backdrop')){this.closeModal();return;}
    const check=element.closest<HTMLElement>('[data-check]');if(check){const id=check.dataset.check!;this.checked.has(id)?this.checked.delete(id):this.checked.add(id);this.renderAssets();return;}
    const favorite=element.closest<HTMLElement>('[data-favorite]');if(favorite){const asset=this.assets.find(a=>a.id===favorite.dataset.favorite)!;await this.act({type:'favorite',ids:[asset.id],favorite:!asset.favorite});return;}
    const favoriteCapability=element.closest<HTMLElement>('[data-capability-favorite]');if(favoriteCapability){const id=favoriteCapability.dataset.capabilityFavorite!,favorite=!this.capabilityFavorites.has(id);await this.api.favoriteCapability(id,favorite);favorite?this.capabilityFavorites.add(id):this.capabilityFavorites.delete(id);this.renderLibrary();return;}
    const card=element.closest<HTMLElement>('[data-asset]');if(card&&!element.matches('input')){this.selected=card.dataset.asset;this.explicitSelection=true;this.root.querySelectorAll<HTMLElement>('[data-asset]').forEach(node=>node.classList.toggle('aw-selected',node.dataset.asset===this.selected));this.renderDetail();if(event.detail<2)this.openDetails(card,event.detail===1?300:0);return;}
    const capability=element.closest<HTMLElement>('[data-capability]');if(capability){this.librarySelected=capability.dataset.capability;this.renderLibrary();this.openDetails(capability);return;}
    const filter=element.closest<HTMLElement>('[data-filter]');if(filter){this.filter=filter.dataset.filter!;this.checked.clear();this.renderAssets();return;}
    const libraryFilter=element.closest<HTMLElement>('[data-library-filter]');if(libraryFilter){this.libraryFilter=libraryFilter.dataset.libraryFilter!;this.renderLibrary();return;}
    const view=element.closest<HTMLElement>('[data-view]');if(view){this.layout=view.dataset.view!;this.renderAssets();return;}
    const format=element.closest<HTMLElement>('[data-format]');if(format){this.convertFormat=format.dataset.format as ConversionOptions['format'];this.root.querySelectorAll<HTMLElement>('[data-format]').forEach(b=>b.classList.toggle('aw-active',b===format));return;}
    const parent=element.closest<HTMLElement>('[data-parent]');if(parent){const asset=this.assets.find(a=>a.id===parent.dataset.parent);if(asset){this.filter=asset.trashedAt?'trash':'all';this.selected=asset.id;this.query='';this.$<HTMLInputElement>('#asset-search').value='';this.renderAssets();}return;}
    const action=element.closest<HTMLElement>('[data-action]')?.dataset.action;if(!action)return;
    if(action==='close-details'){this.closeDetails();return;}
    if(action==='chat'){this.options.close();return;}
    if(action==='assets'||action==='skills'||action==='agents'){if(action!=='assets')this.selectLibrary(action);this.options.panel(action);return;}
    if(action==='close-modal'){this.closeModal();return;}
    if(action==='import'){await this.act({type:'import'});return;}
    if(action==='asset-folder'){await this.act({type:'open-folder'});return;}
    if(action==='reload-assets'){await this.reload();return;}
    if(action==='reload-catalog'){await this.loadCatalog();return;}
    if(action==='clear-search'){this.query='';this.$<HTMLInputElement>('#asset-search').value='';this.renderAssets();return;}
    if(action==='clear-selection'){this.checked.clear();this.renderAssets();return;}
    if(action==='clear-library-search'){this.libraryQuery='';this.libraryFilter='all';this.favoritesOnly=false;this.$<HTMLInputElement>('#capability-search').value='';this.renderLibrary();return;}
    if(action==='preview'||action==='preview-render'||action==='preview-source'||action==='preview-online'){await this.preview(this.previewAsset||this.current(),action==='preview-source',action==='preview-online');return;}
    if(action==='open-external'){const asset=this.previewAsset||this.current();if(asset)await this.act({type:'open-external',id:asset.id});return;}
    if(action==='rename'||action==='edit-tags'){await this.edit(action==='edit-tags');return;}
    if(action==='save-edit'){const field=this.$<HTMLInputElement>('#edit-value');const id=field.dataset.asset!;if(field.dataset.tags==='true')await this.act({type:'tags',id,tags:field.value.split(/[,，]/).map(t=>t.trim()).filter(Boolean)});else await this.act({type:'rename',id,name:field.value});this.closeModal();return;}
    if(action==='trash-selected'||action==='trash-current'||action==='restore-current'){const ids=action==='trash-selected'?[...this.checked]:this.current()?[this.current()!.id]:[];await this.act({type:'trash',ids,trashed:action!=='restore-current'&&this.filter!=='trash'});this.checked.clear();return;}
    if(['reveal','export','source'].includes(action)){const asset=this.current();if(asset)await this.act({type:action as 'reveal'|'export'|'source',id:asset.id});return;}
    if(action.startsWith('download-')){const asset=this.current();if(asset)await this.act({type:action.slice(9) as 'pause'|'resume'|'cancel',id:asset.id});return;}
    if(action==='cancel-conversion'){const asset=this.current();if(asset)await this.act({type:'conversion-cancel',id:asset.id});return;}
    if(action==='send-current'||action==='send-selected'){this.openTask(action==='send-current'&&this.current()?[this.current()!]:this.selectedFiles());return;}
    if(action==='convert-current'||action==='convert-selected'){this.openConvert(action==='convert-current'&&this.current()?[this.current()!]:this.selectedFiles());return;}
    if(action==='run-conversion'){await this.runConversion();return;}
    if(action==='use-capability'){if(this.libraryKind==='skills'){this.taskSkill=this.librarySelected;this.taskAgent=undefined;}else{this.taskAgent=this.librarySelected;this.taskSkill=undefined;}this.openTask(this.explicitSelection?this.selectedFiles():[]);return;}
    if(action==='agent-full'){const id=this.librarySelected;if(!id)return;const data=await this.api.agent(id);const name=this.catalog!.agents.find(a=>a.id===id)?.name||'角色定义';this.modal(name,'角色目标、工作步骤与交付要求。',`<pre class="source-text">${esc(data)}</pre>`);return;}
    if(action==='start-handoff'){await this.startHandoff();return;}
    if(action==='handoff-history'){this.showJobs();await this.refreshJobs();return;}
    if(action==='cancel-handoff'&&this.currentJob){await this.api.cancelHandoff(this.profile.id,this.currentJob);await this.refreshJobs();return;}
    if(action==='copy-handoff'&&this.currentJob){await this.api.copyHandoff(this.profile.id,this.currentJob);this.notify('完整处理指令已复制，可在目标对话中手动检查和发送。');return;}
    if(action==='open-handoff'&&this.currentJob){await this.api.openHandoff(this.profile.id,this.currentJob);return;}
    if(action==='task-prompt'){const profile=this.profile.id;const prompt=await this.api.compose({skillId:this.$<HTMLSelectElement>('#task-skill').value||undefined,agentId:this.$<HTMLSelectElement>('#task-agent').value||undefined,language:this.$<HTMLSelectElement>('#task-language').value,instructions:this.$<HTMLTextAreaElement>('#task-instructions').value,files:this.taskFiles.map(a=>({name:a.name,mime:a.mime,size:a.size}))});if(profile===this.profile.id&&this.root.querySelector('#wb-prompt-preview'))this.html('#prompt-preview',`<pre class="source-text">${esc(prompt)}</pre>`);return;}
  }
  private readonly doubleClick=(event:MouseEvent):void=>{clearTimeout(this.detailTimer);const element=event.target instanceof Element?event.target:undefined;const card=element?.closest<HTMLElement>('[data-asset]');if(card&&!element?.closest('button')){if(this.narrow.matches)this.closeDetails();void this.preview(this.assets.find(a=>a.id===card.dataset.asset)).catch(error=>this.notify(error));}};
  focusSearch():void{if(this.$('#modal-root').childElementCount)return;if(this.narrow.matches&&this.detailOpen)this.closeDetails();this.$<HTMLInputElement>(this.panel==='assets'?'#asset-search':'#capability-search').focus();}
  private readonly keydown=(event:KeyboardEvent):void=>{
    if(event.key==='Escape'){event.preventDefault();event.stopPropagation();this.closeTop();return;}
    if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='k'){event.preventDefault();this.focusSearch();return;}
    const target=event.target instanceof HTMLElement?event.target:undefined;
    if((event.key==='Enter'||event.key===' ')&&target?.matches('[data-asset],[data-capability],[data-action="preview"]')){event.preventDefault();target.click();return;}
    if(event.key==='Tab'){const modal=this.root.querySelector('.aw-modal')||(this.narrow.matches&&this.detailOpen?this.$(this.panel==='assets'?'#asset-detail':'#capability-detail'):undefined);if(!modal)return;const items=[...modal.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]')].filter(e=>e.getClientRects().length);if(!items.length)return;if(event.shiftKey&&document.activeElement===items[0]){event.preventDefault();items.at(-1)!.focus();}else if(!event.shiftKey&&document.activeElement===items.at(-1)){event.preventDefault();items[0].focus();}}
  };
  private readonly dragover=(event:DragEvent):void=>{if(event.dataTransfer?.types.includes('Files')){event.preventDefault();this.$('.asset-main').classList.add('aw-drop-target');}};
  private readonly dragleave=(event:DragEvent):void=>{if(!event.relatedTarget||!this.root.contains(event.relatedTarget as Node))this.$('.asset-main').classList.remove('aw-drop-target');};
  private readonly drop=(event:DragEvent):void=>{if(!event.dataTransfer?.files.length)return;event.preventDefault();this.$('.asset-main').classList.remove('aw-drop-target');const profile=this.profile.id;void this.api.importDropped(profile,[...event.dataTransfer.files]).then(()=>{if(profile===this.profile.id)return this.reload();}).catch(error=>this.notify(error));};
  closeTop():void {if(this.$('#modal-root').children.length)this.closeModal();else if(this.narrow.matches&&this.detailOpen)this.closeDetails();else this.options.close();}
  dispose():void {void this.flushView().catch(()=>{});clearTimeout(this.detailTimer);this.narrow.removeEventListener('change',this.syncDetails);for(const event of ['input','change','click','scroll'])this.root.removeEventListener(event,this.scheduleView,true);this.disposed=true;this.generation++;this.previewGeneration++;this.unsubscribe();this.unsubscribeJobs();this.root.removeEventListener('click',this.click);this.root.removeEventListener('keydown',this.keydown);this.root.removeEventListener('dblclick',this.doubleClick);this.root.removeEventListener('contextmenu',this.contextMenu);this.closeAssetMenu();this.root.removeEventListener('dragover',this.dragover);this.root.removeEventListener('dragleave',this.dragleave);this.root.removeEventListener('drop',this.drop);this.root.classList.remove('aw-detail-open');this.root.innerHTML='';}
}
