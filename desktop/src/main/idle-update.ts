/** App-local activity only; deliberately has no system/global idle API. */
export class IdleUpdate {
 private lastActivity:number;private epoch=0;private checking=false;private installing=false;private probeAfter=0;deadline?:number;
 constructor(private options:{now?:()=>number;idleMs?:number;countdownMs?:number;eligible:()=>boolean;focused:()=>boolean;visible?:()=>boolean;safe:()=>Promise<boolean>;countdown:(deadline?:number)=>void;install:(epoch:number)=>void}){this.lastActivity=this.now();}
 private now():number{return(this.options.now||Date.now)();}
 activity():void{this.lastActivity=this.now();this.epoch++;this.installing=false;this.cancel();}
 cancel():void{if(this.deadline!==undefined){this.deadline=undefined;this.options.countdown();}this.probeAfter=0;}
 private eligible():boolean{return this.options.eligible()&&!this.options.focused()&&this.now()-this.lastActivity>=(this.options.idleMs??1800000);}
 async permit(epoch:number):Promise<boolean>{return epoch===this.epoch&&this.eligible()&&await this.options.safe()&&epoch===this.epoch&&this.eligible();}
 async tick():Promise<void>{
  if(this.checking||this.installing)return;
  if(!this.eligible()){this.cancel();return;}
  if(this.deadline!==undefined&&this.options.visible?.()===false){this.cancel();return;}
  if(this.deadline!==undefined&&this.now()<this.deadline&&this.now()<this.probeAfter)return;
  this.checking=true;const epoch=this.epoch;
  try{
   if(!await this.permit(epoch)){this.cancel();return;}
   if(this.deadline===undefined){this.deadline=this.now()+(this.options.countdownMs??60000);this.options.countdown(this.deadline);}
   else if(this.now()>=this.deadline){this.installing=true;this.cancel();this.options.install(epoch);}
   this.probeAfter=this.now()+5000;
  }catch{this.cancel();}finally{this.checking=false;}
 }
}
