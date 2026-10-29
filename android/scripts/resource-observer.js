/* Synthetic measurement transport only. Never shipped in the application. */
const observerPolicy=STUDIO_OBSERVER_POLICY;
const observerProbe={policy:observerPolicy,started:0,completed:0,failed:0,aborted:0,inflight:0,maxInflight:0,coalesced:0,stormRevisions:0,stormActive:false,intervalMs:500,cadenceSkipped:0};
let observerDirty=false,storm=false,lastStarted=-Infinity;
const originalObservation=observation;
observation=()=>({...originalObservation(),observerProbe:{...observerProbe},streamText:document.querySelector('article p')?.textContent||''});
report=async trigger=>{
  // Only periodic measurement payloads are throttled. Real input/scroll/click
  // events and a coalesced latest-state delivery bypass this cadence gate.
  if(!trigger?.type&&observerProbe.intervalMs>500&&performance.now()-lastStarted<observerProbe.intervalMs){observerProbe.cadenceSkipped++;return;}
  if(observerPolicy==='bounded'&&observerProbe.inflight){observerDirty=true;observerProbe.coalesced++;return;}
  lastStarted=performance.now();
  observerProbe.started++;observerProbe.inflight++;observerProbe.maxInflight=Math.max(observerProbe.maxInflight,observerProbe.inflight);
  const controller=observerPolicy==='bounded'?new AbortController():null;
  const timeout=controller?setTimeout(()=>controller.abort(),2500):null;
  try{
    const response=await fetch('/observe',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(observation()),...(controller?{signal:controller.signal}:{})});
    if(!response.ok)throw new Error('Fixture observer HTTP '+response.status);
    const control=await response.json();storm=control.mutationStorm===true;observerProbe.stormActive=storm;
    if(control.observerIntervalMs===500||control.observerIntervalMs===5000)observerProbe.intervalMs=control.observerIntervalMs;
    observerProbe.completed++;
  }catch(error){observerProbe.failed++;if(error.name==='AbortError')observerProbe.aborted++;}
  finally{
    if(timeout!==null)clearTimeout(timeout);observerProbe.inflight--;
    if(observerPolicy==='bounded'&&observerDirty){observerDirty=false;queueMicrotask(()=>report({type:'coalesced'}));}
  }
};
// Real DOM mutations and duplicate synthetic notifications form a labelled
// recovery hot-path workload. These events never pretend to be trusted input.
setInterval(()=>{
  if(!storm)return;
  const paragraph=document.querySelector('article p');if(!paragraph)return;
  paragraph.textContent='Synthetic streaming revision '+(++observerProbe.stormRevisions);
  for(let i=0;i<8;i++)document.dispatchEvent(new Event('input',{bubbles:true}));
},500);
