import type {WebContents} from 'electron';
export type NativeAgentMetadata={brands:{brand:string;version:string}[];fullVersionList:{brand:string;version:string}[];platform:string;platformVersion:string;architecture:string;model:string;mobile:boolean;bitness:string;wow64:boolean;uaFullVersion?:string};
export type PageEnvironment={userAgent:string;acceptLanguages:string;locale:string;timezoneId:string;metadata:NativeAgentMetadata};
const clients=new WeakMap<WebContents,PageProtocol>();
export function pageProtocol(contents:WebContents):PageProtocol {let client=clients.get(contents);if(!client){client=new PageProtocol(contents);clients.set(contents,client);}return client;}
/** One connection per page: account preferences and file upload share it. */
export class PageProtocol {
 private owned=false;private ready:Promise<void>=Promise.resolve();private environment?:PageEnvironment;
 constructor(private readonly contents:WebContents){contents.debugger.on('detach',()=>{this.owned=false;});}
 private connect():Promise<void>{
  if(this.contents.isDestroyed())return Promise.reject(Error('网页已关闭'));
  if(this.contents.debugger.isAttached()){if(!this.owned)return Promise.reject(Error('网页调试连接被占用，请关闭开发者工具后重试。'));return this.ready;}
  this.contents.debugger.attach('1.3');this.owned=true;
  this.ready=this.environment?this.apply(this.environment):Promise.resolve();return this.ready;
 }
 private async apply(e:PageEnvironment):Promise<void>{
  const send=(method:string,params:Record<string,unknown>)=>this.contents.debugger.sendCommand(method,params);
  const m=e.metadata;
  await send('Emulation.setUserAgentOverride',{userAgent:e.userAgent,acceptLanguage:e.acceptLanguages,userAgentMetadata:{brands:m.brands,fullVersionList:m.fullVersionList,fullVersion:m.uaFullVersion,platform:m.platform,platformVersion:m.platformVersion,architecture:m.architecture,model:m.model,mobile:m.mobile,bitness:m.bitness,wow64:m.wow64}});
  await send('Emulation.setTimezoneOverride',{timezoneId:e.timezoneId});
 }
 snapshot():PageEnvironment|undefined{return this.environment?structuredClone(this.environment):undefined;}
 async configure(environment:PageEnvironment):Promise<void>{this.environment=environment;if(this.owned&&this.contents.debugger.isAttached())this.ready=this.ready.then(()=>this.apply(environment));else await this.connect();await this.ready;}
 async send(method:string,params:Record<string,unknown>):Promise<any>{await this.connect();if(this.contents.isDestroyed())throw Error('网页已关闭');return this.contents.debugger.sendCommand(method,params);}
 // Persistent emulation must stay attached: detaching resets native timezone/language.
 release():void{if(!this.environment&&this.owned&&!this.contents.isDestroyed()&&this.contents.debugger.isAttached())this.contents.debugger.detach();}
}
