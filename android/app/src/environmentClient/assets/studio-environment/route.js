'use strict';
// Fail closed from the first extension instruction, including reconnects and route transitions.
let blocked=true,port=null,latest=-1,instance="",latestToken="",serial=Promise.resolve();
browser.webRequest.onBeforeRequest.addListener(()=>({cancel:blocked}),{urls:['<all_urls>']},['blocking']);
function connect(){
  if(port!==null)return;
  blocked=true;
  try{port=browser.runtime.connectNative('studioEnvironmentRoute');}catch(_){setTimeout(connect,1000);return;}
  const connection=port;
  connection.onDisconnect.addListener(()=>{if(port!==connection)return;blocked=true;latestToken='';port=null;setTimeout(connect,1000);});
  connection.onMessage.addListener(message=>{
    if(port!==connection)return;
    if(message&&message.type==='suspend'){blocked=true;latestToken='';return;}
    if(!message||message.type!=='route'||!Number.isSafeInteger(message.generation)||typeof message.routeToken!=='string')return;
    if(message.instance===instance&&message.generation<latest)return;
    instance=message.instance;latest=message.generation;latestToken=message.routeToken;blocked=true;
    const own=port;
    serial=serial.catch(()=>{}).then(async()=>{
      if(message.generation!==latest||message.instance!==instance||message.routeToken!==latestToken||own!==port)return;
      try{
        let value;
        if(message.kind==='system')value={proxyType:'system'};
        else if(message.kind==='direct')value={proxyType:'none'};
        else if(message.kind==='proxy'&&Number.isInteger(message.port)&&message.port>0&&message.port<65536)value={proxyType:'manual',http:'127.0.0.1:'+message.port,httpProxyAll:true,passthrough:''};
        else if(message.kind==='blocked')value={proxyType:'none'};
        else throw Error('route invalid');
        await browser.proxy.settings.set({value});
        const result=await browser.proxy.settings.get({});
        if(!result||!result.value||result.value.proxyType!==value.proxyType)throw Error('route not applied');
        if(message.kind==='proxy'&&(!String(result.value.http).endsWith('127.0.0.1:'+message.port)||result.value.httpProxyAll!==true))throw Error('proxy not applied');
        if(own!==port||message.generation!==latest||message.instance!==instance||message.routeToken!==latestToken)return;
        blocked=message.kind==='blocked';
        own.postMessage({type:'route-applied',generation:message.generation,instance:message.instance,routeToken:message.routeToken,kind:message.kind,port:message.port||0,ok:true});
      }catch(_){blocked=true;if(own===port)own.postMessage({type:'route-applied',generation:message.generation,instance:message.instance,routeToken:message.routeToken,ok:false});}
    });
  });
}
connect();
