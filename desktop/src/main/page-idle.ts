import type{WebContents}from'electron';import{resumeURL}from'./recovery-store';
/** Unknown pages or attachments are never treated as safe to interrupt. */
function idlePage():boolean{
 const visible=(e:Element)=>!!e.getClientRects().length;
 if([...document.querySelectorAll('input[type="password"],input[autocomplete="one-time-code"]')].some(visible))return false;
 const composer=document.querySelector<HTMLElement>('#prompt-textarea');if(!composer)return false;
 const busy='dialog[open],[role="dialog"],[data-testid="stop-button"],[data-testid="composer-stop-button"],button[aria-label="Stop generating"],button[aria-label="停止生成"],[aria-busy="true"],[role="progressbar"]';
 if([...document.querySelectorAll(busy)].some(visible))return false;
 if([...document.querySelectorAll<HTMLInputElement>('input[type="file"]')].some(e=>e.files?.length))return false;
 const region=composer.closest('form')||composer.parentElement;if(!region)return false;
 const attachments='img,video,audio,[data-testid*="attachment"],[class*="attachment"],[data-testid="file-thumbnail"],[data-testid="file-preview"],[data-testid="upload-progress"]';
 return ![...region.querySelectorAll(attachments)].some(visible);
}
export async function pageSafeForIdle(contents:WebContents,fixtureOrigin?:string):Promise<boolean>{
 if(contents.isDestroyed()||contents.isCrashed()||contents.isLoading()||contents.isCurrentlyAudible()||!resumeURL(contents.getURL(),fixtureOrigin))return false;
 const url=contents.getURL();let timer:ReturnType<typeof setTimeout>|undefined;
 try{return await Promise.race([contents.executeJavaScript('('+idlePage.toString()+')()'),new Promise<false>(r=>{timer=setTimeout(()=>r(false),800);timer.unref();})])===true&&!contents.isDestroyed()&&contents.getURL()===url&&!contents.isLoading();}catch{return false;}finally{clearTimeout(timer);}
}
