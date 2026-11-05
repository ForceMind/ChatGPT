'use strict';
// Gecko 158 Android supports proxy.onRequest; proxy.settings deliberately throws there.
// Keep the request guard closed until the exact listener and route tuple are installed.
let blocked=true,port=null,latest=-1,instance='',latestToken='',serial=Promise.resolve(),applied=null;
function cancelRequest(){return {cancel:blocked};}
browser.webRequest.onBeforeRequest.addListener(cancelRequest,{urls:['<all_urls>']},['blocking']);
function selectProxy(){
  // A callback queued during a transition must never return a direct/system fallback.
  if(blocked||!applied||applied.kind==='blocked')return [{type:'http',host:'127.0.0.1',port:1,failoverTimeout:1},null];
  // In Gecko's filter, null explicitly removes the prior/default proxy. type:direct does not.
  if(applied.kind==='direct')return null;
  if(applied.kind==='proxy')return [{type:'http',host:'127.0.0.1',port:applied.port},null];
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
  connection.onDisconnect.addListener(()=>{if(port!==connection)return;blocked=true;applied=null;latestToken='';port=null;setTimeout(connect,1000);});
  connection.onMessage.addListener(message=>{
    if(port!==connection)return;
    if(message&&message.type==='suspend'){blocked=true;applied=null;latestToken='';return;}
    if(!message||message.type!=='route'||!Number.isSafeInteger(message.generation)||typeof message.routeToken!=='string')return;
    if(message.instance===instance&&message.generation<latest)return;
    instance=message.instance;latest=message.generation;latestToken=message.routeToken;blocked=true;
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
browser.proxy.onError.addListener(error=>{
  if(!applied||!port)return;
  const message=applied;
  failed(message,port,error);
});
connect();
