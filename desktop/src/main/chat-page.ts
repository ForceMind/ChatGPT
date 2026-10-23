import type {WebContents} from 'electron';
import {pageProtocol} from './page-protocol';
import {isChatURL} from './policy';
import {randomUUID} from 'node:crypto';
import {chatDOM,normalizedPrompt} from './chat-dom';
export type PageObservation={composer:boolean;input:boolean;send:boolean;text:string;attachments:string[];removers:number;busy:boolean;error:boolean;messages:number;lastMessage:string;generating:boolean;login:boolean;url:string};
// Read only the current composer's controls and acknowledgement. No conversation
// history, credentials, browser storage or private ChatGPT endpoints are fetched.
function observe():PageObservation {
  const visible=(e:Element)=>!!(e as HTMLElement).getClientRects().length;
  const {composer,scope,send,text}=chatDOM();
  const inputs=[...document.querySelectorAll<HTMLInputElement>('input[type="file"]')].filter(i=>!i.webkitdirectory&&!i.accept.startsWith('image/')&&!i.disabled);
  const removes=[...(scope||document).querySelectorAll<HTMLElement>('button[aria-label*="Remove"],button[aria-label*="remove"],button[aria-label*="移除"],button[aria-label*="删除附件"],[data-testid="remove-file"]')].filter(visible);
  const attachments=[...(scope||document).querySelectorAll<HTMLElement>('[data-testid="attachment"],[data-testid="file-thumbnail"],[data-attachment-name],[title]')].filter(visible).map(e=>e.getAttribute('data-attachment-name')||e.getAttribute('title')||e.innerText||'');
  const messages=[...document.querySelectorAll<HTMLElement>('[data-message-author-role="user"]')];
  return {composer:!!composer,input:!!inputs.length,send:!!send,text,attachments:[...attachments,...removes.map(e=>(e.getAttribute('aria-label')||'')+' '+(e.parentElement?.textContent||''))],removers:removes.length,busy:!!scope?.querySelector('[role="progressbar"],button[aria-label*="Cancel upload"],button[aria-label*="取消上传"]'),error:!!scope?.querySelector('[role="alert"]'),messages:messages.length,lastMessage:messages.at(-1)?.innerText||'',generating:!!document.querySelector('[data-testid="stop-button"]'),login:!composer&&!![...document.querySelectorAll<HTMLElement>('a,button')].find(e=>visible(e)&&/^(log in|登录)$/i.test(e.innerText.trim())),url:location.href};
}
export class ChatPage {
  private readonly token=randomUUID();
  private usedChooser=false;private readonly previousThrottling:boolean;
  constructor(readonly contents:WebContents,private readonly fixtureOrigin:string|undefined,private readonly check:()=>void) {this.previousThrottling=contents.getBackgroundThrottling();contents.setBackgroundThrottling(false);}
  private guard():void{this.check();if(this.contents.isDestroyed())throw Error('目标标签页已关闭');const url=this.contents.getURL();if(!isChatURL(url)&&!(this.fixtureOrigin&&url.startsWith(this.fixtureOrigin+'/')))throw Error('目标账号尚未进入 ChatGPT，请在目标标签页完成登录后重新发起任务。');}
  private async script<T>(body:string):Promise<T>{this.guard();const result=await this.contents.executeJavaScript('(()=>{const chatDOM='+chatDOM.toString()+';return '+body+'})()',true);this.guard();return result as T;}
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
  async draft(prompt:string,progress:(written:number,total:number)=>void=()=>{}):Promise<void>{
    if((await this.state()).text.trim())throw Error('新标签页输入框已有内容，为避免覆盖草稿已停止。');
    const token=JSON.stringify(this.token),expected=normalizedPrompt(prompt);
    await this.script(`(()=>{const old=window.__profilesHandoffInput;old?.cleanup();const state={token:${token},writing:false,edited:false,cleanup:()=>{}};const manual=e=>{if(e.isTrusted&&(e.type!=='input'||!state.writing))state.edited=true;};for(const type of ['pointerdown','keydown','input'])window.addEventListener(type,manual,true);state.cleanup=()=>{for(const type of ['pointerdown','keydown','input'])window.removeEventListener(type,manual,true);};window.__profilesHandoffInput=state;})()`);
    let current='',attempts=0;
    while(attempts++<3){
      const existing=await this.state();current=normalizedPrompt(existing.text);
      if(current===expected){progress(expected.length,expected.length);return;}
      if(current&&!expected.startsWith(current))throw Error('输入框内容与处理指令不一致，已保留现有文字并停止；可复制完整指令手动处理。');
      const result=await this.command('Runtime.evaluate',{expression:'('+chatDOM.toString()+')().composer',returnByValue:false});const objectId=result.result?.objectId;
      if(!objectId)throw Error('指令输入框正在重新加载，已停止；附件与现有草稿保留。');
      try{
        await this.command('DOM.focus',{objectId});
        await this.script(`(()=>{const state=window.__profilesHandoffInput,{composer:e,text}=chatDOM();if(!state||state.token!==${token}||state.edited)throw Error('检测到你正在编辑目标页面，已停止自动填写并保留草稿。');if(!e||text!==${JSON.stringify(existing.text)})throw Error('输入框已变化，已停止自动填写并保留草稿。');e.focus();if(e instanceof HTMLTextAreaElement)e.setSelectionRange(e.value.length,e.value.length);else{const range=document.createRange();range.selectNodeContents(e);range.collapse(false);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);}state.writing=true;})()`);
        const raw=existing.text.replace(/\r\n?/g,'\n').replace(/\u00a0/g,' '),normalized=prompt.replace(/\r\n?/g,'\n');
        if(raw&&!normalized.startsWith(raw))throw Error('指令格式与网页内容不一致，已停止自动补写；请手动检查。');
        await this.command('Input.insertText',{text:raw?normalized.slice(raw.length):prompt});
      }finally{await this.command('Runtime.releaseObject',{objectId}).catch(()=>{});await this.script(`(()=>{const s=window.__profilesHandoffInput;if(s?.token===${token})s.writing=false;})()`).catch(()=>{});}
      let stable=0,last='';const deadline=Date.now()+2000;
      while(Date.now()<deadline){const value=await this.state();const text=normalizedPrompt(value.text);progress(text.length,expected.length);stable=text===last?stable+1:0;last=text;if(text===expected&&stable>=2)return;await new Promise(r=>setTimeout(r,200));}
    }
    current=normalizedPrompt((await this.state()).text);throw Error(`指令写入未完成（${current.length}/${expected.length} 字符），已停止发送；附件和现有文字保留。`);
  }
  async sendOnce(prompt:string,names:string[],beforeClick:()=>void,afterClick:()=>void=()=>{}):Promise<string>{
    const filesReady=(s:PageObservation)=>s.removers===names.length&&names.every(n=>s.attachments.some(a=>a.includes(n)));
    const before=await this.wait(s=>{if(normalizedPrompt(s.text)!==normalizedPrompt(prompt))throw Error('发送检查时指令已改变，已保留现有草稿并停止。');return s.send&&!s.busy&&filesReady(s);},15000,'指令已完整写入，但发送按钮仍不可用或附件仍在处理；未点击发送，请打开目标对话检查。');
    beforeClick();
    await this.script(`(()=>{const now=(${observe.toString()})();const names=${JSON.stringify(names)},norm=${normalizedPrompt.toString()};if(norm(now.text)!==${JSON.stringify(normalizedPrompt(prompt))}||now.busy||now.removers!==names.length||!names.every(n=>now.attachments.some(a=>a.includes(n))))throw Error('发送前内容或附件已改变');if(window.__profilesHandoffInput?.edited)throw Error('检测到手动编辑，已停止自动发送');const b=chatDOM().send;if(!b||b.disabled||b.getAttribute('aria-disabled')==='true')throw Error('发送控件已改变');b.click();})()`);afterClick();
    const normalize=(s:string)=>s.replace(/\s+/g,' ').trim();const needle=normalize(prompt).slice(0,160);
    const ack=await this.wait(s=>s.messages>before.messages&&normalize(s.lastMessage).includes(needle)&&!s.text.trim(),25000,'网页未确认收到消息，请检查目标对话；应用不会自动重发。');
    const clean=new URL(ack.url);clean.search='';clean.hash='';return clean.href;
  }
  dispose():void{if(this.contents.isDestroyed())return;void this.script(`(()=>{const s=window.__profilesHandoffInput;if(s?.token===${JSON.stringify(this.token)}){s.cleanup();delete window.__profilesHandoffInput;}})()`).catch(()=>{});if(this.usedChooser&&this.contents.debugger.isAttached())void pageProtocol(this.contents).send('Page.setInterceptFileChooserDialog',{enabled:false}).catch(()=>{});pageProtocol(this.contents).release();this.contents.setBackgroundThrottling(this.previousThrottling);}
}
