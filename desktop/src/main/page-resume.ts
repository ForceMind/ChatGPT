import type{WebContents}from'electron';
import type{PageResume}from'../shared/recovery';
import{resumeURL}from'./recovery-store';
// Only the chat composer and reading anchor are observed. Login forms,
// attachments, page HTML, cookies and conversation text are never serialized.
function pageState(){
 const visible=(e:Element)=>!!e.getClientRects().length;
 if([...document.querySelectorAll('input[type="password"],input[autocomplete="one-time-code"]')].some(visible))return null;
 const composer=[...document.querySelectorAll<HTMLElement>('#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]')].find(visible);
 const messages=[...document.querySelectorAll<HTMLElement>('[data-message-id],[data-message-author-role]')];
 if(!composer&&!messages.length)return null;
 const candidates=new Set<Element>();if(document.scrollingElement)candidates.add(document.scrollingElement);let parent:Element|null=document.querySelector('[data-message-id],[data-message-author-role]')||composer||null;while(parent){candidates.add(parent);parent=parent.parentElement;}
 const containers=[...candidates].filter((e):e is HTMLElement=>!!e&&e instanceof HTMLElement&&visible(e)&&e.scrollHeight>e.clientHeight+40&&e.clientHeight>100);
 const scroller=containers.sort((a,b)=>b.scrollHeight-a.scrollHeight)[0]||document.documentElement;
 const rect=scroller===document.scrollingElement?{top:0}:scroller.getBoundingClientRect();
 const first=messages.find(e=>e.getBoundingClientRect().bottom>rect.top+10);
 const value=first?.getAttribute('data-message-id')||first?.id;
 const draft=composer instanceof HTMLTextAreaElement?composer.value:composer?.innerText||'';
 if(draft.length>100000)throw Error('草稿超过本地恢复容量，请先手动保存。');
 return{draft,scroll:Math.max(0,scroller.scrollTop),ratio:Math.max(0,Math.min(1,scroller.scrollTop/Math.max(1,scroller.scrollHeight-scroller.clientHeight))),anchor:first&&value?{kind:first.hasAttribute('data-message-id')?'message':'id',value,offset:first.getBoundingClientRect().top-rect.top}:undefined};
}
function restorePage(record:PageResume,restoreDraft:boolean,forceReading:boolean){
 const host=window as typeof window&{__profilesResume?:{token:string;cancelled:boolean;cleanup:()=>void}};
 const token=record.tabId+':'+record.capturedAt;
 if(host.__profilesResume?.token!==token){host.__profilesResume?.cleanup();const current={token,cancelled:false,cleanup:()=>{}};const events=['pointerdown','wheel','touchstart','keydown'];const onInput=(event:Event)=>{if(event.isTrusted)current.cancelled=true;};current.cleanup=()=>events.forEach(name=>window.removeEventListener(name,onInput,true));events.forEach(name=>window.addEventListener(name,onInput,{capture:true,passive:true}));host.__profilesResume=current;}
 if(host.__profilesResume?.cancelled)return 'cancelled';
 const visible=(e:Element)=>!!e.getClientRects().length;
 if([...document.querySelectorAll('input[type="password"],input[autocomplete="one-time-code"]')].some(visible))return 'cancelled';
 const composer=[...document.querySelectorAll<HTMLElement>('#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]')].find(visible);
 const candidates=new Set<Element>();if(document.scrollingElement)candidates.add(document.scrollingElement);let parent:Element|null=document.querySelector('[data-message-id],[data-message-author-role]')||composer||null;while(parent){candidates.add(parent);parent=parent.parentElement;}
 const containers=[...candidates].filter((e):e is HTMLElement=>!!e&&e instanceof HTMLElement&&visible(e)&&e.scrollHeight>e.clientHeight+40&&e.clientHeight>100);
 const scroller=containers.sort((a,b)=>b.scrollHeight-a.scrollHeight)[0]||document.documentElement;
 const max=Math.max(0,scroller.scrollHeight-scroller.clientHeight);
 const anchor=record.anchor;const element=anchor?(anchor.kind==='message'?document.querySelector<HTMLElement>('[data-message-id="'+CSS.escape(anchor.value)+'"]'):document.getElementById(anchor.value)):undefined;
 if(!forceReading&&record.scroll>0&&!element&&max<record.scroll-5)return 'waiting';
 if(element&&anchor){const top=scroller===document.scrollingElement?0:scroller.getBoundingClientRect().top;scroller.scrollTop+=element.getBoundingClientRect().top-top-anchor.offset;}
 else scroller.scrollTop=record.scroll<=max?record.scroll:record.ratio*max;
 if(!restoreDraft||!record.draft)return 'restored';
 if(!composer)return 'waiting';
 const current=composer instanceof HTMLTextAreaElement?composer.value:composer.innerText;
 if(current.trim())return current===record.draft?'restored':'conflict';
 const lastUser=[...document.querySelectorAll<HTMLElement>('[data-message-author-role="user"]')].at(-1)?.innerText||'';
 const normalize=(s:string)=>s.replace(/\s+/g,' ').trim();
 if(normalize(lastUser)===normalize(record.draft))return 'already-sent';
 if(document.querySelector('[data-testid="stop-button"]'))return 'conflict';
 composer.focus();
 if(document.activeElement!==composer&&!composer.contains(document.activeElement))return 'conflict';
 if(composer instanceof HTMLTextAreaElement){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(composer,record.draft);composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:record.draft}));}
 else if(!document.execCommand('insertText',false,record.draft))return 'conflict';
 const actual=composer instanceof HTMLTextAreaElement?composer.value:composer.innerText;
 return actual===record.draft?'restored':'conflict';
}
export async function captureResume(contents:WebContents,profileId:string,tabId:string,fixtureOrigin?:string):Promise<PageResume|undefined>{
 if(contents.isDestroyed()||contents.isCrashed()||contents.isLoadingMainFrame())return;
 const url=resumeURL(contents.getURL(),fixtureOrigin);if(!url)return;
 const state=await contents.executeJavaScript('('+pageState.toString()+')()');
 if(!state||contents.isDestroyed()||resumeURL(contents.getURL(),fixtureOrigin)!==url)return;
 return{schema:1,profileId,tabId,url,...state,capturedAt:Date.now()};
}
export async function applyResume(contents:WebContents,record:PageResume,fixtureOrigin?:string,forceReading=false):Promise<'restored'|'conflict'|'waiting'|'already-sent'|'cancelled'|undefined>{
 if(contents.isDestroyed()||resumeURL(contents.getURL(),fixtureOrigin)!==record.url)return;
 return contents.executeJavaScript('('+restorePage.toString()+')('+JSON.stringify(record)+',true,'+JSON.stringify(forceReading)+')',true);
}

export async function finishResume(contents:WebContents,record:PageResume):Promise<void>{
 if(contents.isDestroyed())return;const token=JSON.stringify(record.tabId+':'+record.capturedAt);
 await contents.executeJavaScript('(()=>{const state=window.__profilesResume;if(state?.token==='+token+'){state.cleanup();delete window.__profilesResume;}})()');
}
