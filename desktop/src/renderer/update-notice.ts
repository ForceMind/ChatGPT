const deadline=Number(new URL(location.href).searchParams.get('deadline'));
const label=document.getElementById('seconds')!;
const paint=()=>{label.textContent=String(Math.max(0,Math.ceil((deadline-Date.now())/1000)));};
paint();setInterval(paint,1000);
document.getElementById('cancel')!.addEventListener('click',()=>{(window as typeof window&{updateNotice:{cancel:()=>void}}).updateNotice.cancel();});
