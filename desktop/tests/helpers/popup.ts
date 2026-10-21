import{expect,type ElectronApplication,type Page}from'@playwright/test';
/** Resume Playwright's paused popup target before waiting on parent page JS. */
export async function openNativePopup(app:ElectronApplication,script:string,contentsId?:number):Promise<Page>{
 const pending=app.waitForEvent('window');
 await app.evaluate(({BrowserWindow},{script,contentsId})=>{
  const state=globalThis as typeof globalThis&{popupTestResult?:boolean;popupTestError?:string};state.popupTestResult=undefined;state.popupTestError=undefined;
  const shell=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;
  const views=shell.contentView.children as Electron.WebContentsView[];
  const contents=(contentsId?views.find(v=>v.webContents.id===contentsId):views.find(v=>v.getVisible()))!.webContents;
  void contents.executeJavaScript(script,true).then(value=>{state.popupTestResult=!!value;}).catch(error=>{state.popupTestError=String(error);});
 },{script,contentsId});
 const page=await pending;const session=await app.context().newCDPSession(page);await session.send('Runtime.runIfWaitingForDebugger');await page.waitForLoadState();
 await expect.poll(()=>app.evaluate(()=>({value:(globalThis as typeof globalThis&{popupTestResult?:boolean}).popupTestResult,error:(globalThis as typeof globalThis&{popupTestError?:string}).popupTestError}))).toEqual({value:true,error:undefined});

 await session.detach();return page;
}
