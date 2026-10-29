/* Synthetic measurement transport only. Never shipped in the application. */
const observerPolicy=STUDIO_OBSERVER_POLICY;
const observerProbe={policy:observerPolicy,started:0,completed:0,failed:0,aborted:0,inflight:0,maxInflight:0,coalesced:0,stormRevisions:0,stormActive:false};
let observerDirty=false,storm=false;
const originalObservation=observation;
observation=()=>({...originalObservation(),observerProbe:{...observerProbe},streamText:document.querySelector('article p')?.textContent||''});
report=async()=>{
  if(observerPolicy==='bounded'&&observerProbe.inflight){observerDirty=true;observerProbe.coalesced++;return;}
  observerProbe.started++;observerProbe.inflight++;observerProbe.maxInflight=Math.max(observerProbe.maxInflight,observerProbe.inflight);
  const controller=observerPolicy==='bounded'?new AbortController():null;
  const timeout=controller?setTimeout(()=>controller.abort(),2500):null;
  try{
    const response=await fetch('/observe',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(observation()),...(controller?{signal:controller.signal}:{})});
    if(!response.ok)throw new Error('Fixture observer HTTP '+response.status);
    const control=await response.json();storm=control.mutationStorm===true;observerProbe.stormActive=storm;observerProbe.completed++;
  }catch(error){observerProbe.failed++;if(error.name==='AbortError')observerProbe.aborted++;}
  finally{
    if(timeout!==null)clearTimeout(timeout);observerProbe.inflight--;
    if(observerPolicy==='bounded'&&observerDirty){observerDirty=false;queueMicrotask(report);}
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
