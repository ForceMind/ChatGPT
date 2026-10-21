import type{WebContents}from'electron';
import{isAllowedURL}from'./policy';
// The website supplies its own desktop titlebar CSS. Those drag rectangles are
// native window hit-test regions in Electron and can swallow real mouse clicks.
// Only our trusted toolbar may declare draggable regions.
export const REMOTE_INPUT_CSS='html,body,body *,body *::before,body *::after{-webkit-app-region:no-drag!important;app-region:no-drag!important}';
export function protectRemoteInput(contents:WebContents,fixtureOrigin?:string):void{
 const apply=()=>{if(contents.isDestroyed()||!isAllowedURL(contents.getURL(),fixtureOrigin))return;void contents.insertCSS(REMOTE_INPUT_CSS,{cssOrigin:'user'}).catch(()=>{/* navigation/destruction may invalidate a stylesheet request */});};
 contents.on('dom-ready',apply);if(!contents.isLoading())apply();
}
