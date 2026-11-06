'use strict';
(()=>{
  if(window.top!==window)return;
  let port=null,auth=null,sequence=0,epoch=0,timer=null,maxTimer=null,last='',currentUrl=location.href,closed=false,activeRestore=null;
  const documentId=crypto.randomUUID();
  function eligible(){
    let u;try{u=new URL(location.href);}catch(_){return false;}
    if(u.searchParams.get('temporary-chat')==='true'||/^\/(auth|api|backend-api|login|logout|signup|signin)(\/|$)/.test(u.pathname))return false;
    // Temporary chat does not leave even a draft or message preview on disk.
    if(document.querySelector('[data-testid="temporary-chat-on"],[data-testid="temporary-chat-badge"],[data-is-temporary-chat="true"]'))return false;
    const main=document.querySelector('main');
    if(main&&Array.from(main.querySelectorAll('button[aria-pressed="true"],h1,h2,[role="status"]')).some(n=>/^(Temporary Chat|临时聊天|临時聊天)$/i.test((n.innerText||'').trim())))return false;
    return true;
  }
  function composer(){
    const n=document.querySelector('#prompt-textarea[contenteditable="true"],#prompt-textarea:not([type="password"]):not([type="email"]),[data-testid="prompt-textarea"][contenteditable="true"]');
    if(!n||n.closest('[data-private],[autocomplete="one-time-code"]')||n.getAttribute('type')==='password'||n.getAttribute('type')==='email'||n.getAttribute('autocomplete')==='one-time-code')return null;
    return n;
  }
  function draft(n=composer()){return !n?'':('value'in n?String(n.value):String(n.innerText||'')).slice(0,100000);}
  function scrollRoot(){
    const message=document.querySelector('main [data-message-author-role]');let node=message||document.querySelector('main');
    while(node&&node!==document.documentElement){const style=getComputedStyle(node);if(node.scrollHeight-node.clientHeight>20&&/auto|scroll|overlay/.test(style.overflowY))return node;node=node.parentElement;}
    return document.scrollingElement||document.documentElement;
  }
  function snapshot(){
    if(!eligible())return null;
    const sc=scrollRoot(),max=Math.max(0,sc.scrollHeight-sc.clientHeight);
    const nodes=Array.from(document.querySelectorAll('main [data-message-author-role="user"],main [data-message-author-role="assistant"]')).slice(-60);
    let preview=nodes.map(n=>(n.dataset.messageAuthorRole==='user'?'我：':'ChatGPT：')+(n.innerText||'')).join('\n\n');
    preview=preview.slice(-100000);
    return {url:location.href,draft:draft(),preview,scroll:Math.max(0,Math.round(sc.scrollTop)),ratio:max?Math.max(0,Math.min(1,sc.scrollTop/max)):0,capturedAt:Date.now()};
  }
  function send(type,extra={}){
    if(!port||!auth||closed)return;
    port.postMessage(Object.assign({type,url:location.href,document:documentId,token:auth.token,epoch,sequence:++sequence,accountId:auth.accountId,storageId:auth.storageId,tabId:auth.tabId},extra));
  }
  function pageBusy(){
    try{
      const visible=node=>!!node&&!!node.getClientRects().length;
      const markers='[aria-busy="true"],[role="progressbar"],[data-testid="stop-button"],[data-testid="stop-generation"],[data-is-streaming="true"],[data-message-streaming="true"],[data-state="uploading"],[data-status="uploading"],[data-testid="remove-file-button"],[data-testid="attachment"],button[aria-label*="Stop generating" i],button[aria-label*="Stop response" i],button[aria-label*="停止生成"],button[aria-label*="停止回答"],button[aria-label*="Remove file" i],button[aria-label*="Remove attachment" i],button[aria-label*="移除附件"],button[aria-label*="删除附件"]';
      return Array.from(document.querySelectorAll(markers)).some(visible)||Array.from(document.querySelectorAll('input[type="file"]')).some(node=>node.files&&node.files.length>0);
    }catch(_){return true;}
  }
  function capture(type='capture',extra={}){
    if(location.href!==currentUrl){currentUrl=location.href;last='';auth=null;if(port)port.disconnect();return;}
    const state=snapshot();if(!state){send('ineligible',extra);return;}
    const fingerprint=JSON.stringify({...state,capturedAt:0});
    if(type==='capture'&&fingerprint===last)return;last=fingerprint;
    const reply={snapshot:state,...extra};if(type==='checkpoint'&&typeof extra.request==='string')reply.busy=pageBusy();
    send(type,reply);
  }
  function queue(){if(timer!==null)clearTimeout(timer);if(maxTimer===null)maxTimer=setTimeout(()=>{maxTimer=null;if(timer!==null){clearTimeout(timer);timer=null;}capture();},5000);timer=setTimeout(()=>{timer=null;if(maxTimer!==null){clearTimeout(maxTimer);maxTimer=null;}capture();},1200);}
  function restore(message){
    if(typeof message.request!=='string')return;const request=message.request;activeRestore=request;
    const before=snapshot(),n=composer();if(!before){send('restore-unavailable',{request});return;}
    if(before.draft!==message.expectedDraft){send('conflict',{snapshot:before,request});return;}
    const wanted=String(message.draft||'');if(wanted.length>100000)return;
    if(before.draft!==wanted){
      if(!n){send('restore-unavailable',{request});return;}
      // Only the exact composer is edited, after native conflict approval. Never submit.
      const event=new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertReplacementText',data:wanted});
      if(!n.dispatchEvent(event)){send('restore-unavailable',{request});return;}
      n.focus();
      if('value'in n){const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(n),'value')?.set;if(setter)setter.call(n,wanted);else n.value=wanted;}
      else{
        const selection=getSelection(),range=document.createRange();range.selectNodeContents(n);selection.removeAllRanges();selection.addRange(range);
        if(!document.execCommand('insertText',false,wanted)){send('restore-unavailable',{request});return;}
      }
      n.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertReplacementText',data:wanted}));
    }
    const scroll=Number(message.scroll),ownAuth=auth,ownEpoch=epoch;
    function position(attempt){
      if(auth!==ownAuth||epoch!==ownEpoch||activeRestore!==request)return;
      const sc=scrollRoot(),max=Math.max(0,sc.scrollHeight-sc.clientHeight);
      if(Number.isFinite(scroll)&&scroll>max+80&&attempt<24){setTimeout(()=>position(attempt+1),250);return;}
      if(Number.isFinite(scroll)&&scroll>=0)sc.scrollTo({top:Math.min(scroll,max),behavior:'instant'});
      if(Number.isFinite(scroll)&&scroll>max+80){send('restore-unavailable',{request});return;}
      last='';setTimeout(()=>{if(activeRestore===request)capture('restored',{request});},150);
    }
    position(0);
  }
  let localActivation=null,keyActivationTarget=null;
  function activeLocal(value){return !!value&&value===localActivation&&!!auth&&value.token===auth.token&&value.epoch===epoch&&performance.now()-value.at<=5000&&eligible();}
  function activateLocal(method){
    if(!auth||!eligible()||!navigator.userActivation?.isActive)return null;
    const value={id:crypto.randomUUID(),at:performance.now(),token:auth.token,epoch,anchor:null};localActivation=value;
    send('local-activation',{activation:value.id,method,active:true});return value;
  }
  function downloadAnchor(event){return event.composedPath().find(node=>node&&node.nodeType===1&&node.tagName==='A'&&node.hasAttribute('download'));}
  document.addEventListener('keydown',event=>{
    if(event.isTrusted&&!event.repeat&&(event.key==='Enter'||event.key===' ')&&event.target?.closest?.('button,a')){keyActivationTarget=event.target;activateLocal('keyboard');}
  },true);
  document.addEventListener('click',event=>{
    if(event.isTrusted){if(!(event.detail===0&&keyActivationTarget===event.target&&activeLocal(localActivation)))activateLocal('click');keyActivationTarget=null;}
    const value=localActivation,anchor=downloadAnchor(event);
    if(!anchor||!activeLocal(value)||!navigator.userActivation?.isActive)return;
    const address=anchor.href,kind=address.startsWith('blob:')?'blob':(address.startsWith('data:')?'data':null);if(!kind)return;
    value.anchor={kind,promise:crypto.subtle.digest('SHA-256',new TextEncoder().encode(address)).then(bytes=>Array.from(new Uint8Array(bytes),item=>item.toString(16).padStart(2,'0')).join(''))};
  },true);
  async function localProof(message){
    const value=localActivation;
    if(!value||message.activation!==value.id||typeof message.request!=='string')return;
    let uriHash=null,anchor=value.anchor;
    try{if(anchor)uriHash=await anchor.promise;}catch(_){uriHash=null;}
    const active=activeLocal(value)&&!!navigator.userActivation?.isActive&&(!anchor||!!uriHash);
    send('local-export-proof',{request:message.request,activation:value.id,active,hasUriDigest:!!anchor,detached:!anchor,...(anchor?{kind:anchor.kind,uriHash:uriHash||''}:{})});
  }
  function connect(){
    if(closed||!eligible())return;auth=null;sequence=0;
    try{port=browser.runtime.connectNative('studioEnvironmentRecovery');}catch(_){setTimeout(connect,500);return;}
    const own=port;
    port.onMessage.addListener(message=>{
      if(own!==port||!message)return;
      if(message.type==='auth'&&typeof message.token==='string'&&Number.isSafeInteger(message.epoch)){
        auth=message;epoch=message.epoch;activeRestore=null;last='';capture('ready');
      }else if(auth&&message.token===auth.token&&message.epoch===epoch){
        if(message.type==='local-export-challenge')localProof(message);
        if(message.type==='capture')capture('checkpoint',typeof message.request==='string'?{request:message.request}:{});
        if(message.type==='restore')restore(message);if(message.type==='cancel-restore')activeRestore=null;
      }
    });
    port.onDisconnect.addListener(()=>{if(own===port){port=null;auth=null;if(!closed)setTimeout(connect,350);}});
  }
  document.addEventListener('input',event=>{const n=composer();if(n&&(event.target===n||n.contains(event.target)))queue();},true);
  document.addEventListener('scroll',queue,{passive:true,capture:true});
  new MutationObserver(queue).observe(document.documentElement,{childList:true,subtree:true,characterData:true});
  window.addEventListener('pagehide',()=>{capture('checkpoint');closed=true;if(port)port.disconnect();});
  window.addEventListener('pageshow',()=>{if(closed){closed=false;currentUrl=location.href;connect();}});
  setInterval(()=>{if(location.href!==currentUrl)capture();if(!closed&&!port&&eligible())connect();},500);
  connect();
})();
