import {expect,type ElectronApplication,type Page} from '@playwright/test';
/** Electron exposes embedded WebContentsViews as pages too. Select the shell
 * by its trusted URL rather than whichever page wins the startup race. */
export async function shellWindow(app:ElectronApplication,viewport?:{width:number;height:number}):Promise<Page>{
 await expect.poll(()=>app.windows().some(page=>page.url()==='app://shell/index.html'),{timeout:15000}).toBe(true);
 const page=app.windows().find(page=>page.url()==='app://shell/index.html')!;
 if(viewport){await app.evaluate(({BrowserWindow},size)=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!.setContentSize(size.width,size.height),viewport);await expect.poll(()=>page.evaluate(()=>innerWidth)).toBe(viewport.width);}
 return page;
}
