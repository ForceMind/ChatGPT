const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function observer(policy){
 const calls=[],timers=new Map(),intervals=[],paragraph={textContent:'original'},events=[];let timer=0;
 const ctx=vm.createContext({observation:()=>({draft:ctx.draft}),draft:'first',testNow:0,performance:{now:()=>ctx.testNow},report:()=>{},AbortController,queueMicrotask,
  setTimeout:fn=>{timers.set(++timer,fn);return timer;},clearTimeout:id=>timers.delete(id),setInterval:fn=>intervals.push(fn),
  document:{querySelector:()=>paragraph,dispatchEvent:e=>events.push(e)},Event:class{constructor(type){this.type=type;this.isTrusted=false;}},
  fetch:(_url,options)=>new Promise((resolve,reject)=>{const call={options,resolve:control=>resolve({ok:true,json:async()=>control})};calls.push(call);options.signal?.addEventListener('abort',()=>reject(Object.assign(new Error('aborted'),{name:'AbortError'})));})});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../resource-observer.js'),'utf8').replace('STUDIO_OBSERVER_POLICY',JSON.stringify(policy)),ctx);
 return {ctx,calls,timers,intervals,paragraph,events,stats:()=>JSON.parse(vm.runInContext('JSON.stringify(observerProbe)',ctx))};
}
test('bounded transport holds one request and flushes latest coalesced state',async()=>{
 const o=observer('bounded');const first=o.ctx.report();o.ctx.draft='latest';await o.ctx.report();await o.ctx.report();assert.equal(o.calls.length,1);assert.equal(o.stats().maxInflight,1);
 o.calls[0].resolve({});await first;await flush();assert.equal(o.calls.length,2);assert.equal(JSON.parse(o.calls[1].options.body).draft,'latest');
 [...o.timers.values()][0]();await flush();assert.equal(o.stats().aborted,1);assert.equal(o.stats().inflight,0);assert.equal(o.timers.size,0);
});
test('legacy control retains concurrent requests without silently changing workload',async()=>{
 const o=observer('legacy');const work=[o.ctx.report(),o.ctx.report(),o.ctx.report()];assert.equal(o.calls.length,3);assert.equal(o.stats().maxInflight,3);assert.equal(o.timers.size,0);o.calls.forEach(c=>c.resolve({}));await Promise.all(work);assert.equal(o.stats().inflight,0);
});
test('streaming fixture mutates real paragraph and labels synthetic notifications',async()=>{
 const o=observer('bounded');const p=o.ctx.report();o.calls[0].resolve({mutationStorm:true});await p;o.intervals[0]();assert.match(o.paragraph.textContent,/Synthetic streaming revision 1/);assert.equal(o.events.length,8);assert(o.events.every(e=>e.type==='input'&&!e.isTrusted));
});
test('quiet cadence gates only periodic payloads, not real input or latest state',async()=>{
 const o=observer('bounded');let p=o.ctx.report();o.calls[0].resolve({observerIntervalMs:5000});await p;
 o.ctx.testNow=4900;await o.ctx.report();assert.equal(o.calls.length,1);assert.equal(o.stats().cadenceSkipped,1);
 p=o.ctx.report({type:'input'});assert.equal(o.calls.length,2);o.calls[1].resolve({observerIntervalMs:5000});await p;
 o.ctx.testNow=10000;p=o.ctx.report();assert.equal(o.calls.length,3);o.calls[2].resolve({observerIntervalMs:500});await p;
 o.ctx.testNow=10001;p=o.ctx.report();assert.equal(o.calls.length,4);o.calls[3].resolve({});await p;
});
function adapter(tuned){
 const messages=[],listeners={},port={postMessage:m=>messages.push(m),onMessage:{addListener:fn=>port.receive=fn},onDisconnect:{addListener:fn=>port.disconnect=fn}};
 const standard={value:'first'},rich={innerText:'rich',isContentEditable:true,contains:()=>false},paragraph={textContent:'transcript'};let later;
 const ctx=vm.createContext({URL,crypto:require('node:crypto').webcrypto,location:{href:'http://127.0.0.1:8765/recovery?account=A&tab=0&page=detail'},browser:{runtime:{connectNative:()=>port}},document:{getElementById:id=>id==='standard'?standard:rich,querySelector:()=>({children:[paragraph]}),documentElement:{scrollHeight:2000}},queueMicrotask,
  addEventListener:(type,fn)=>listeners[type]=fn,setTimeout:fn=>{later=fn;return 1;},clearTimeout:()=>{},MutationObserver:class{constructor(fn){this.fn=fn;}observe(){}},innerWidth:400,innerHeight:600,scrollY:100});
 ctx.window=ctx;ctx.top=ctx;
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../environment-poc/src/main/assets/managed-recovery/recovery.js'),'utf8'),ctx);
 port.receive({type:'auth',token:'first-token',epoch:1,resourceTuned:tuned});
 return {ctx,port,messages,listeners,standard,paragraph,runLater:()=>later?.()};
}
test('optimized adapter skips identical capture but retains changed draft, checkpoint and handshake',async()=>{
 const a=adapter(true);assert.equal(a.messages.at(-1).type,'ready');const count=a.messages.length;a.listeners.input();a.listeners.input();await flush();assert.equal(a.messages.length,count);
 a.standard.value='changed';a.listeners.input();await flush();assert.equal(a.messages.at(-1).snapshot.standard,'changed');
 a.port.receive({type:'checkpoint',request:'required',token:'first-token',epoch:1});assert.equal(a.messages.at(-1).type,'checkpoint');assert.equal(a.messages.at(-1).request,'required');
 const before=a.messages.length;a.port.receive({type:'hello-request'});assert.equal(a.messages.length,before+1);
 a.paragraph.textContent='new transcript';a.listeners.scroll();a.runLater();assert.equal(a.messages.at(-1).snapshot.transcript,'new transcript');
 a.port.receive({type:'auth',token:'new-token',epoch:2,resourceTuned:true});assert.equal(a.messages.at(-1).token,'new-token');
});
test('baseline adapter preserves repeated capture control behavior',async()=>{
 const a=adapter(false);const before=a.messages.length;a.listeners.input();a.listeners.input();await flush();assert.equal(a.messages.length,before+2);
});
test('native recovery policy retains equal progress snapshots until ready',async()=>{
 const a=adapter(false);let before=a.messages.length;a.listeners.input();a.listeners.input();await flush();assert.equal(a.messages.length,before+2);
 a.port.receive({type:'capture-policy',resourceTuned:true,token:'first-token',epoch:1});
 a.listeners.input();await flush();before=a.messages.length;a.listeners.input();await flush();assert.equal(a.messages.length,before);
 a.port.receive({type:'capture-policy',resourceTuned:false,token:'first-token',epoch:1});before=a.messages.length;a.listeners.input();a.listeners.input();await flush();assert.equal(a.messages.length,before+2);
});
