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
    if(location.href!==currentUrl){cancelLocalStream();currentUrl=location.href;last='';auth=null;if(port)port.disconnect();return;}
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
  let localActivation=null,keyActivationTarget=null,localStream=null,clickWrapper=null,clickPrototype=null,clickDescriptor=null;
  const anchorHref=Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype,'href').get;
  const anchorName=Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype,'download').get;
  const nodeDocument=Object.getOwnPropertyDescriptor(Node.prototype,'ownerDocument').get;
  const hasAttribute=Element.prototype.hasAttribute,nativeClick=HTMLElement.prototype.click;
  function activeLocal(value){return !!value&&value===localActivation&&!!auth&&value.token===auth.token&&value.epoch===epoch&&performance.now()-value.at<=5000&&eligible();}
  function activateLocal(method){
    if(!auth||!eligible()||!navigator.userActivation?.isActive)return null;
    const value={id:crypto.randomUUID(),at:performance.now(),token:auth.token,epoch,anchor:null};localActivation=value;
    send('local-activation',{activation:value.id,method,active:true});return value;
  }
  function downloadAnchor(event){return event.composedPath().find(node=>node&&node.nodeType===1&&node.tagName==='A'&&node.hasAttribute('download'));}
  function cancelLocalStream(){
    const value=localStream;if(!value)return;localStream=null;value.cancelled=true;
    if(value.prepareTimer)clearTimeout(value.prepareTimer);
    if(value.wait){clearTimeout(value.wait.timer);value.wait.reject(new Error('cancelled'));value.wait=null;}
    value.abort.abort();if(value.reader)value.reader.cancel().catch(()=>{});
  }
  function localStreamAlive(value){return value===localStream&&!value.cancelled&&!closed&&!!auth&&value.auth===auth&&value.epoch===epoch&&value.url===location.href&&eligible();}
  function interceptLocal(anchor){
    let address,name;
    try{
      // Native WebIDL getters perform brand checks, ignoring page expandos/getters.
      if(nodeDocument.call(anchor)!==document||!hasAttribute.call(anchor,'download'))return false;
      address=anchorHref.call(anchor);name=anchorName.call(anchor);
    }catch(_){return false;}
    const kind=address.startsWith('blob:')?'blob':address.startsWith('data:')?'data':null;
    const activation=localActivation;
    if(!kind||address.startsWith('blob:null/')||!activeLocal(activation)||!navigator.userActivation?.isActive)return false;
    // A valid activation authorizes only the first local download, never a navigation.
    if(activation.used||localStream)return true;
    activation.used=true;
    const promise=crypto.subtle.digest('SHA-256',new TextEncoder().encode(address)).then(bytes=>Array.from(new Uint8Array(bytes),item=>item.toString(16).padStart(2,'0')).join(''));
    activation.anchor={kind,promise};
    const value={request:crypto.randomUUID(),auth,epoch,url:location.href,activation:activation.id,abort:new AbortController(),reader:null,cancelled:false,started:false,wait:null,sequence:0,bytes:0,phase:'fetch'};localStream=value;
    value.prepareTimer=setTimeout(()=>value.abort.abort(),5000);
    (async()=>{
      try{
        // Gecko 158 explicitly preserves the page's original bound fetch as content.fetch.
        // Only a captured blob/data URI is passed; remote fetching is never supported here.
        const response=await content.fetch(address,{signal:value.abort.signal,redirect:'error',credentials:'omit'});
        value.phase='response';
        if(!localStreamAlive(value)||!response.ok||response.redirected||!response.body||!response.url.startsWith(kind+':'))throw new Error('source');
        let mime=(response.headers.get('content-type')||'application/octet-stream').split(';',1)[0].trim().toLowerCase();
        if(!/^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(mime))mime='application/octet-stream';
        value.phase='length';const rawLength=response.headers.get('content-length');let length=-1;
        if(rawLength!==null){if(!/^\d+$/.test(rawLength))throw new Error('length');length=Number(rawLength);if(!Number.isSafeInteger(length)||length>256*1024*1024)throw new Error('length');}
        value.phase='reader';value.reader=response.body.getReader();value.phase='digest';const uriHash=await promise;
        value.phase='activation';
        if(!localStreamAlive(value)||!activeLocal(activation)||!navigator.userActivation?.isActive)throw new Error('activation');
        value.phase='intent';send('local-export-intent',{request:value.request,activation:value.activation,kind,uriHash,name:name.slice(0,512),mime,length});
      }catch(_){if(localStreamAlive(value)){if(auth?.localDiagnostics)send('local-diagnostic',{stage:'stream-prepare',phase:value.phase,active:!!navigator.userActivation?.isActive,eligible:eligible(),trusted:false,anchored:true});send('local-export-error',{request:value.request,activation:value.activation});}if(localStream===value)cancelLocalStream();}
      finally{clearTimeout(value.prepareTimer);value.prepareTimer=null;}
    })();
    return true;
  }
  function installLocalClick(){
    if(clickWrapper)return;
    try{
      const prototype=HTMLAnchorElement.prototype.wrappedJSObject||HTMLAnchorElement.prototype;
      const descriptor=Object.getOwnPropertyDescriptor(prototype,'click');
      // Preserve a site's own pre-existing click implementation rather than replacing it.
      if(descriptor&&descriptor.value!==nativeClick)return;
      const wrapper=exportFunction(function(...args){if(interceptLocal(this))return;return Reflect.apply(nativeClick,this,args);},prototype);
      Object.defineProperty(prototype,'click',{value:wrapper,writable:true,enumerable:false,configurable:true});
      clickPrototype=prototype;clickDescriptor=descriptor;clickWrapper=wrapper;
    }catch(_){if(auth?.localDiagnostics)send('local-diagnostic',{stage:'wrapper',active:false,eligible:eligible(),trusted:false,anchored:false});}
  }
  function restoreLocalClick(){
    if(!clickWrapper)return;
    try{if(Object.getOwnPropertyDescriptor(clickPrototype,'click')?.value===clickWrapper){if(clickDescriptor)Object.defineProperty(clickPrototype,'click',clickDescriptor);else delete clickPrototype.click;}}catch(_){}
    clickWrapper=null;clickPrototype=null;clickDescriptor=null;
  }
  async function readLocalStream(message){
    const value=localStream;
    if(!value||value.request!==message.request||value.started||typeof message.readKey!=='string'||!localStreamAlive(value)||!value.reader)return;
    value.started=true;value.readKey=message.readKey;
    try{
      while(localStreamAlive(value)){
        value.phase='read';
        const result=await value.reader.read();if(result.done)break;
        const bytes=result.value;
        for(let offset=0;offset<bytes.length;offset+=16384){
          if(!localStreamAlive(value))throw new Error('source');
          const packet=bytes.subarray(offset,Math.min(offset+16384,bytes.length));value.bytes+=packet.length;if(value.bytes>256*1024*1024)throw new Error('length');
          const chunk=btoa(String.fromCharCode(...packet)),chunkSequence=++value.sequence;
          value.phase='chunk';
          await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{if(value.wait?.sequence===chunkSequence){value.wait=null;reject(new Error('timeout'));}},30000);value.wait={sequence:chunkSequence,resolve,reject,timer};send('local-export-chunk',{request:value.request,readKey:value.readKey,chunkSequence,chunk});});
        }
      }
      if(!localStreamAlive(value))throw new Error('source');
      value.phase='eof';send('local-export-end',{request:value.request,readKey:value.readKey,chunkSequence:value.sequence+1,length:value.bytes});
      value.reader.releaseLock();value.reader=null;
    }catch(_){if(localStreamAlive(value)){if(auth?.localDiagnostics)send('local-diagnostic',{stage:'stream-read',phase:value.phase,active:false,eligible:eligible(),trusted:false,anchored:true});send('local-export-error',{request:value.request,activation:value.activation});}if(localStream===value)cancelLocalStream();}
  }
  document.addEventListener('keydown',event=>{
    if(event.isTrusted&&!event.repeat&&(event.key==='Enter'||event.key===' ')&&event.target?.closest?.('button,a')){keyActivationTarget=event.target;activateLocal('keyboard');}
  },true);
  document.addEventListener('click',event=>{
    if(auth?.localDiagnostics&&event.isTrusted)send('local-diagnostic',{stage:'click',active:!!navigator.userActivation?.isActive,eligible:eligible(),trusted:event.isTrusted,anchored:!!downloadAnchor(event)});
    if(event.isTrusted){if(!(event.detail===0&&keyActivationTarget===event.target&&activeLocal(localActivation)))activateLocal('click');keyActivationTarget=null;}
    const value=localActivation,anchor=downloadAnchor(event);
    if(anchor&&interceptLocal(anchor))event.preventDefault();
  },true);
  async function localProof(message){
    const value=localActivation;
    if(!value||message.activation!==value.id||typeof message.request!=='string')return;
    let uriHash=null,anchor=value.anchor;
    try{if(anchor)uriHash=await anchor.promise;}catch(_){uriHash=null;}
    const active=activeLocal(value)&&!!navigator.userActivation?.isActive&&(!anchor||!!uriHash);
    if(auth?.localDiagnostics)send('local-diagnostic',{stage:'proof',active:!!navigator.userActivation?.isActive,eligible:eligible(),trusted:false,anchored:!!anchor});
    send('local-export-proof',{request:message.request,activation:value.id,active,hasUriDigest:!!anchor,detached:!anchor,...(anchor?{kind:anchor.kind,uriHash:uriHash||''}:{})});
  }
  function connect(){
    if(closed||!eligible())return;auth=null;sequence=0;
    try{port=browser.runtime.connectNative('studioEnvironmentRecovery');}catch(_){setTimeout(connect,500);return;}
    const own=port;
    port.onMessage.addListener(message=>{
      if(own!==port||!message)return;
      if(message.type==='auth'&&typeof message.token==='string'&&Number.isSafeInteger(message.epoch)){
        cancelLocalStream();
        auth=message;epoch=message.epoch;activeRestore=null;last='';capture('ready');
      }else if(auth&&message.token===auth.token&&message.epoch===epoch){
        if(message.type==='local-export-cancel'&&localStream?.request===message.request)cancelLocalStream();
        if(message.type==='local-export-read')readLocalStream(message);
        if(message.type==='local-export-credit'){const value=localStream,wait=value?.wait;if(value?.request===message.request&&value.readKey===message.readKey&&wait?.sequence===message.chunkSequence){clearTimeout(wait.timer);value.wait=null;wait.resolve();}}
        if(message.type==='local-export-challenge')localProof(message);
        if(message.type==='capture')capture('checkpoint',typeof message.request==='string'?{request:message.request}:{});
        if(message.type==='restore')restore(message);if(message.type==='cancel-restore')activeRestore=null;
      }
    });
    port.onDisconnect.addListener(()=>{if(own===port){cancelLocalStream();port=null;auth=null;if(!closed)setTimeout(connect,350);}});
  }
  document.addEventListener('input',event=>{const n=composer();if(n&&(event.target===n||n.contains(event.target)))queue();},true);
  document.addEventListener('scroll',queue,{passive:true,capture:true});
  new MutationObserver(queue).observe(document.documentElement,{childList:true,subtree:true,characterData:true});
  window.addEventListener('pagehide',()=>{cancelLocalStream();restoreLocalClick();capture('checkpoint');closed=true;if(port)port.disconnect();});
  window.addEventListener('pageshow',()=>{if(closed){closed=false;currentUrl=location.href;installLocalClick();connect();}});
  setInterval(()=>{if(location.href!==currentUrl)capture();if(!closed&&!port&&eligible())connect();},500);
  installLocalClick();connect();
})();
