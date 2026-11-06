'use strict';
// Gecko 158 Android supports proxy.onRequest; proxy.settings deliberately throws there.
// Keep the request guard closed until the exact listener and route tuple are installed.
let blocked=true,port=null,latest=-1,instance='',latestToken='',serial=Promise.resolve(),applied=null;
const TICKET='x-studio-environment-ticket',BIND='about:blank#studio-network-bind-';
const pending=new Map(),requests=new Map(),selections=new Map();
function cancelRequest(details){return beforeRequest(details).catch(()=>({cancel:true}));}
browser.webRequest.onBeforeRequest.addListener(cancelRequest,{urls:['<all_urls>']},['blocking']);
function unavailableProxy(){return [{type:'http',host:'127.0.0.1',port:1,failoverTimeout:1},null];}
function selectProxy(details){
  if(blocked||!applied||applied.kind==='blocked')return unavailableProxy();
  // Neither API event is assumed to run first. The first one captures the route;
  // the second must agree with that immutable tuple, never silently select a new one.
  let route=applied;
  if(details&&details.type!=='websocket'){
    route=requests.get(details.requestId)?.route||selections.get(details.requestId)||applied;
    if(!equalRoute(route))return unavailableProxy();
    if(selections.size>=512&&!selections.has(details.requestId))return unavailableProxy();
    selections.set(details.requestId,{...route,uri:details.url});
  }
  if(route.kind==='direct')return null;
  if(route.kind==='proxy')return [{type:'http',host:'127.0.0.1',port:route.port},null];
  return undefined;
}
function current(message,connection){return connection===port&&message.generation===latest&&message.instance===instance&&message.routeToken===latestToken;}
function receipt(message,ok,extra={}){
  return Object.assign({type:'route-applied',generation:message.generation,instance:message.instance,
    routeToken:message.routeToken,kind:message.kind,port:message.port||0,ok,
    implementation:'proxy.onRequest'},extra);
}
function failed(message,connection,error){
  if(!current(message,connection))return;
  blocked=true;applied=null;
  connection.postMessage(receipt(message,false,{error:String(error?.message||error).slice(0,300)}));
}
function connect(){
  if(port!==null)return;
  blocked=true;
  try{port=browser.runtime.connectNative('studioEnvironmentRoute');}catch(_){setTimeout(connect,1000);return;}
  const connection=port;
  connection.onDisconnect.addListener(()=>{if(port!==connection)return;blocked=true;applied=null;latestToken='';port=null;cancelPending();setTimeout(connect,1000);});
  connection.onMessage.addListener(message=>{
    if(port!==connection)return;
    if(handleReply(message,connection))return;
    if(message&&message.type==='suspend'){blocked=true;applied=null;latestToken='';cancelPending();return;}
    if(!message||message.type!=='route'||!Number.isSafeInteger(message.generation)||typeof message.routeToken!=='string')return;
    if(message.instance===instance&&message.generation<latest)return;
    instance=message.instance;latest=message.generation;latestToken=message.routeToken;blocked=true;cancelPending();
    serial=serial.catch(()=>{}).then(async()=>{
      if(!current(message,connection))return;
      try{
        if(!['system','direct','proxy','blocked'].includes(message.kind))throw Error('invalid route kind');
        if(message.kind==='proxy'&&(!Number.isInteger(message.port)||message.port<1||message.port>65535))throw Error('invalid loopback port');
        const event=browser.proxy?.onRequest;
        if(!event||typeof event.addListener!=='function'||typeof event.hasListener!=='function')throw Error('proxy.onRequest unavailable');
        if(event.hasListener(selectProxy))event.removeListener(selectProxy);
        const needsHandler=message.kind==='direct'||message.kind==='proxy';
        applied=Object.freeze({kind:message.kind,port:message.port||0,generation:message.generation,
          instance:message.instance,routeToken:message.routeToken});
        if(needsHandler)event.addListener(selectProxy,{urls:['<all_urls>']});
        // A parent API round trip follows listener registration before acknowledging native navigation.
        await browser.runtime.getBrowserInfo();
        if(!current(message,connection))return;
        const handlerInstalled=event.hasListener(selectProxy);
        const guardInstalled=browser.webRequest.onBeforeRequest.hasListener(cancelRequest);
        if(handlerInstalled!==needsHandler||!guardInstalled||applied.kind!==message.kind||applied.port!==(message.port||0))throw Error('route listener confirmation failed');
        blocked=message.kind==='blocked';
        connection.postMessage(receipt(message,true,{handlerInstalled,guardInstalled}));
      }catch(error){failed(message,connection,error);}
    });
  });
}
function equalRoute(route){return !!route&&!!applied&&route.instance===applied.instance&&route.generation===applied.generation&&route.routeToken===applied.routeToken&&route.kind===applied.kind&&route.port===applied.port;}
function finishWait(call,value){const wait=pending.get(call);if(!wait)return;pending.delete(call);clearTimeout(wait.timer);wait.resolve(value);}
function cancelPending(){for(const call of Array.from(pending.keys()))finishWait(call,null);requests.clear();selections.clear();}
function rpc(type,data,bind=false){
  if(blocked||!port||pending.size>=512)return Promise.resolve(null);
  return new Promise(resolve=>{
    const call=crypto.randomUUID(),wait={resolve,bind,tab:data.tab,denied:false,approved:null,timer:null};
    wait.timer=setTimeout(()=>{if(port)port.postMessage({type:'request-cancel',call});finishWait(call,null);},12000);
    pending.set(call,wait);port.postMessage({type,call,...data});
  });
}
function handleReply(message,connection){
  if(!message||!['bind-required','request-approved','request-result'].includes(message.type))return false;
  const wait=pending.get(message.call);if(!wait)return true;
  if(message.type==='bind-required'){
    if(!wait.bind||typeof message.nonce!=='string'||message.url!==BIND+message.nonce){finishWait(message.call,null);return true;}
    browser.tabs.update(wait.tab,{url:message.url}).then(()=>{
      connection.postMessage({type:'request-cancel',call:message.call});finishWait(message.call,null);
    },error=>{
      // DENY is necessary, but never sufficient: the independent Native lease ACK is also required.
      if(!/^(?:java\.lang\.Exception: )?tabs\.update is not supported$/.test(String(error?.message||error))){connection.postMessage({type:'request-cancel',call:message.call});finishWait(message.call,null);return;}
      wait.denied=true;if(wait.approved)finishWait(message.call,wait.approved);
    });
  }else if(message.type==='request-approved'){
    if(!message.ok){finishWait(message.call,null);return true;}
    if(message.ownerBound!==wait.bind||typeof message.ticket!=='string'){finishWait(message.call,null);return true;}
    wait.approved=message;if(!wait.bind||wait.denied)finishWait(message.call,message);
  }else finishWait(message.call,message.ok?message:null);
  return true;
}
async function beforeRequest(details){
  if(blocked||!applied)return {cancel:true};
  if(details.type==='websocket'||!/^https?:/.test(details.url))return {cancel:false};
  let request=requests.get(details.requestId);
  const selected=selections.get(details.requestId);
  const route=request?.route||selected||{...applied,uri:details.url};
  if(!equalRoute(route)||selected&&!equalRoute(selected)||!request&&selected&&selected.uri!==details.url)return {cancel:true};
  selections.set(details.requestId,{...route,uri:details.url});
  if(request){
    if(!equalRoute(request.route))return {cancel:true};
    const result=await rpc('request-redirect',{ticket:request.ticket,uri:details.url});
    if(!result||blocked||!equalRoute(route))return {cancel:true};request.uri=details.url;return {cancel:false};
  }
  if(requests.size>=256)return {cancel:true};
  const result=await rpc('request-start',{requestId:details.requestId,tab:Number.isSafeInteger(details.tabId)?details.tabId:-1,uri:details.url,method:details.method,...route},Number.isSafeInteger(details.tabId)&&details.tabId>=0);
  if(!result||blocked||!equalRoute(route))return {cancel:true};
  request={ticket:result.ticket,route,uri:details.url};requests.set(details.requestId,request);return {cancel:false};
}
browser.webRequest.onHeadersReceived.addListener(async details=>{
  try{
  const headers=(details.responseHeaders||[]).filter(header=>header.name.toLowerCase()!==TICKET);
  if(details.type==='websocket'||!/^https?:/.test(details.url))return {responseHeaders:headers};
  const request=requests.get(details.requestId);if(!request||blocked||!equalRoute(request.route)||request.uri!==details.url)return {cancel:true};
  const value=name=>(headers.find(header=>header.name.toLowerCase()===name)?.value||'');
  const mime=value('content-type').split(';')[0].trim().toLowerCase();
  const download=/attachment/i.test(value('content-disposition'))||!mime||mime==='application/pdf'||!(/^(?:text\/|image\/|audio\/|video\/|font\/)/.test(mime)||['application/json','application/javascript','application/wasm','application/xml','application/xhtml+xml'].includes(mime));
  const result=await rpc('request-response',{ticket:request.ticket,uri:details.url,download});
  if(!result||blocked||!equalRoute(request.route))return {cancel:true};
  headers.push({name:TICKET,value:request.ticket});return {responseHeaders:headers};
  }catch(_){return {cancel:true};}
},{urls:['<all_urls>']},['blocking','responseHeaders']);
function ended(details,success){const request=requests.get(details.requestId);requests.delete(details.requestId);selections.delete(details.requestId);if(request&&port)port.postMessage({type:'request-finished',ticket:request.ticket,success});}
browser.webRequest.onCompleted.addListener(details=>ended(details,true),{urls:['<all_urls>']});
browser.webRequest.onErrorOccurred.addListener(details=>ended(details,false),{urls:['<all_urls>']});
browser.proxy.onError.addListener(error=>{
  if(!applied||!port)return;
  const message=applied;
  failed(message,port,error);
});
connect();
