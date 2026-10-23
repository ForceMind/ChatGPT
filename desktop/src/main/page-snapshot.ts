import type {WebContents} from 'electron';
import type {PageSnapshot} from '../shared/recovery';
import {resumeURL} from './recovery-store';

// Extract inert text only. Never save HTML, page scripts, cookies or form values.
function readConversation() {
 const visible=(e:Element)=>!!e.getClientRects().length;
 if([...document.querySelectorAll('input[type="password"],input[autocomplete="one-time-code"]')].some(visible))return null;
 const roles=[...document.querySelectorAll<HTMLElement>('[data-message-author-role]')];
 const elements=roles.length?roles:[...document.querySelectorAll<HTMLElement>('[data-message-id]')];
 if(!elements.length)return null;
 const messages:{role:string;text:string}[]=[];let remaining=100000,truncated=false;
 for(const element of elements){
  const text=element.innerText;if(!text.trim())continue;
  if(remaining<=0||messages.length>=300){truncated=true;break;}
  const part=text.slice(0,remaining);remaining-=part.length;truncated ||=part.length<text.length;
  const role=element.getAttribute('data-message-author-role');messages.push({role:role==='user'?'user':role==='assistant'?'assistant':'message',text:part});
 }
 return{title:document.title.slice(0,240),messages,truncated};
}
export async function captureSnapshot(contents:WebContents,fixtureOrigin?:string):Promise<PageSnapshot|undefined>{
 if(contents.isDestroyed()||contents.isCrashed()||contents.isLoadingMainFrame())return;
 const original=contents.getURL(),url=resumeURL(original,fixtureOrigin);if(!url)return;
 // Persistent conversations only; do not archive unsent new or temporary chats.
 if(!(fixtureOrigin&&url.startsWith(fixtureOrigin+'/'))&&!/^\/(?:c|g\/[^/]+\/c)\/[^/]+$/.test(new URL(url).pathname))return;
 const text=await contents.executeJavaScript('('+readConversation.toString()+')()');
 if(!text||contents.isDestroyed()||contents.getURL()!==original)return;
 let image:string|undefined;
 try{const capture=await contents.capturePage(undefined,{stayHidden:true});if(!capture.isEmpty()){const bytes=capture.resize({width:Math.min(1120,capture.getSize().width)}).toJPEG(55);if(bytes.length<=300000)image='data:image/jpeg;base64,'+bytes.toString('base64');}}catch{/* Transcript remains available if Chromium cannot capture the viewport. */}
 if(contents.isDestroyed()||contents.getURL()!==original)return;
 return{...text,url,image,capturedAt:Date.now()};
}
