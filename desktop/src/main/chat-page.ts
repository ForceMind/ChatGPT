import type {WebContents} from 'electron';
import {pageProtocol} from './page-protocol';
import {isChatURL} from './policy';
export type PageObservation={composer:boolean;input:boolean;send:boolean;text:string;attachments:string[];removers:number;busy:boolean;error:boolean;messages:number;lastMessage:string;generating:boolean;login:boolean;url:string};
// Read only the current composer's controls and acknowledgement. No conversation
// history, credentials, browser storage or private ChatGPT endpoints are fetched.
function observe():PageObservation {
  const visible=(e:Element)=>!!(e as HTMLElement).getClientRects().length;
  const composer=[...document.querySelectorAll<HTMLElement>('#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]')].find(visible);
  const scope=composer?.closest('form')||composer?.closest('[data-type="unified-composer"]')||composer?.parentElement?.parentElement;
  const send=scope?.querySelector<HTMLButtonElement>('button[data-testid="send-button"],button[aria-label="Send prompt"],button[aria-label="发送提示"],button[aria-label="发送消息"],button[aria-label="Send message"]');
  const inputs=[...document.querySelectorAll<HTMLInputElement>('input[type="file"]')].filter(i=>!i.webkitdirectory&&!i.accept.startsWith('image/')&&!i.disabled);
  const removes=[...(scope||document).querySelectorAll<HTMLElement>('button[aria-label*="Remove"],button[aria-label*="remove"],button[aria-label*="移除"],button[aria-label*="删除附件"],[data-testid="remove-file"]')].filter(visible);
  const attachments=[...(scope||document).querySelectorAll<HTMLElement>('[data-testid="attachment"],[data-testid="file-thumbnail"],[data-attachment-name],[title]')].filter(visible).map(e=>e.getAttribute('data-attachment-name')||e.getAttribute('title')||e.innerText||'');
  const messages=[...document.querySelectorAll<HTMLElement>('[data-message-author-role="user"]')];
  const text=composer instanceof HTMLTextAreaElement?composer.value:composer?.innerText||'';
  return {composer:!!composer,input:!!inputs.length,send:!!send&&!send.disabled&&visible(send),text,attachments:[...attachments,...removes.map(e=>(e.getAttribute('aria-label')||'')+' '+(e.parentElement?.textContent||''))],removers:removes.length,busy:!!scope?.querySelector('[role="progressbar"],button[aria-label*="Cancel upload"],button[aria-label*="取消上传"]'),error:!!scope?.querySelector('[role="alert"]'),messages:messages.length,lastMessage:messages.at(-1)?.innerText||'',generating:!!document.querySelector('[data-testid="stop-button"]'),login:!composer&&!![...document.querySelectorAll<HTMLElement>('a,button')].find(e=>visible(e)&&/^(log in|登录)$/i.test(e.innerText.trim())),url:location.href};
}
const composerSelector='#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]';
export class ChatPage {
  private usedChooser=false;private readonly previousThrottling:boolean;
  constructor(readonly contents:WebContents,private readonly fixtureOrigin:string|undefined,private readonly check:()=>void) {this.previousThrottling=contents.getBackgroundThrottling();contents.setBackgroundThrottling(false);}
  private guard():void{this.check();if(this.contents.isDestroyed())throw Error('目标标签页已关闭');const url=this.contents.getURL();if(!isChatURL(url)&&!(this.fixtureOrigin&&url.startsWith(this.fixtureOrigin+'/')))throw Error('目标账号尚未进入 ChatGPT，请在目标标签页完成登录后重新发起任务。');}
  private async script<T>(body:string):Promise<T>{this.guard();const result=await this.contents.executeJavaScript(body,true);this.guard();return result as T;}
  state():Promise<PageObservation>{return this.script('('+observe.toString()+')()');}
  private async command(method:string,params:Record<string,unknown>):Promise<any>{this.guard();if(method==='Page.setInterceptFileChooserDialog')this.usedChooser=true;const result=await pageProtocol(this.contents).send(method,params);this.guard();return result;}
  async wait(predicate:(s:PageObservation)=>boolean,timeout:number,message:string):Promise<PageObservation>{const end=Date.now()+timeout;while(Date.now()<end){this.check();const url=this.contents.isDestroyed()?'':this.contents.getURL();const initializing=!url||url==='about:blank'||url.startsWith('file:');if(!initializing&&!this.contents.isDestroyed()&&!this.contents.isLoadingMainFrame()){const s=await this.state();if(s.login)throw Error('目标账号尚未登录，请打开目标标签页完成登录后重新发起。');if(s.error)throw Error('网页提示附件或输入错误，请打开目标标签页查看。');if(predicate(s))return s;}await new Promise(r=>setTimeout(r,250));}throw Error(message);}
  async ready():Promise<void>{await this.wait(s=>s.composer,45000,'未能找到 ChatGPT 输入框，可能需要登录或网页结构已更新。');}
  async upload(files:string[],names:string[]):Promise<void>{
    let state=await this.state();
    if(!state.input){await this.command('Page.setInterceptFileChooserDialog',{enabled:true});await this.script(`(()=>{const b=document.querySelector('button[data-testid="composer-plus-btn"]');if(b)b.click();})()`);if(!(await this.state()).input)await this.script(`(()=>{const b=[...document.querySelectorAll('[role="menuitem"],button')].find(e=>/upload (photos|files)|add (photos|files)|上传照片|上传文件|添加照片|添加文件/i.test(e.textContent||''));if(b)b.click();})()`);state=await this.wait(s=>s.input,6000,'当前网页未提供通用文件上传控件，未发送任何消息。');}
    const result=await this.command('Runtime.evaluate',{expression:`(()=>[...document.querySelectorAll('input[type="file"]')].find(i=>!i.webkitdirectory&&!i.accept.startsWith('image/')&&!i.disabled))()`,returnByValue:false});
    const objectId=result.result?.objectId;if(!objectId)throw Error('网页文件上传控件不可用');
    try{await this.command('DOM.setFileInputFiles',{files,objectId});}finally{await this.command('Runtime.releaseObject',{objectId}).catch(()=>{});}
    let stable=0;await this.wait(s=>{const all=names.every(name=>s.attachments.some(t=>t.includes(name)));const ready=!s.busy&&s.removers===names.length&&all;stable=ready?stable+1:0;return stable>=3;},90000,'未能确认全部附件上传完成，任务停在目标标签页，请手动检查。');
  }
  async draft(prompt:string):Promise<void>{
    if((await this.state()).text.trim())throw Error('新标签页输入框已有内容，为避免覆盖草稿已停止。');
    await this.script(`(()=>{const e=[...document.querySelectorAll(${JSON.stringify(composerSelector)})].find(e=>e.getClientRects().length);e?.focus();})()`);
    await this.command('Input.insertText',{text:prompt});
    await this.wait(s=>s.text.replace(/\r\n/g,'\n').trim()===prompt.trim()&&s.send&&!s.busy,10000,'处理指令未完整进入输入框或发送按钮不可用，已停止发送。');
  }
  async sendOnce(prompt:string,names:string[],beforeClick:()=>void):Promise<string>{
    const before=await this.state();const filesReady=(s:PageObservation)=>s.removers===names.length&&names.every(n=>s.attachments.some(a=>a.includes(n)));if(before.text.trim()!==prompt.trim()||!before.send||before.busy||!filesReady(before))throw Error('网页尚未准备好发送，已停止。');
    beforeClick();
    await this.script(`(()=>{const now=(${observe.toString()})();const names=${JSON.stringify(names)};if(now.text.trim()!==${JSON.stringify(prompt.trim())}||now.busy||now.removers!==names.length||!names.every(n=>now.attachments.some(a=>a.includes(n))))throw Error('发送前内容或附件已改变');const e=[...document.querySelectorAll(${JSON.stringify(composerSelector)})].find(e=>e.getClientRects().length);const scope=e?.closest('form')||e?.closest('[data-type="unified-composer"]')||e?.parentElement?.parentElement;const b=scope?.querySelector('button[data-testid="send-button"],button[aria-label="Send prompt"],button[aria-label="发送提示"],button[aria-label="发送消息"],button[aria-label="Send message"]');if(!b||b.disabled)throw Error('发送控件已改变');b.click();})()`);
    const normalize=(s:string)=>s.replace(/\s+/g,' ').trim();const needle=normalize(prompt).slice(0,160);
    const ack=await this.wait(s=>s.messages>before.messages&&normalize(s.lastMessage).includes(needle)&&!s.text.trim(),25000,'网页未确认收到消息，请检查目标对话；应用不会自动重发。');
    const clean=new URL(ack.url);clean.search='';clean.hash='';return clean.href;
  }
  dispose():void{if(this.contents.isDestroyed())return;if(this.usedChooser&&this.contents.debugger.isAttached())void pageProtocol(this.contents).send('Page.setInterceptFileChooserDialog',{enabled:false}).catch(()=>{});pageProtocol(this.contents).release();this.contents.setBackgroundThrottling(this.previousThrottling);}
}
