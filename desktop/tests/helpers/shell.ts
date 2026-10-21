import {expect,type ElectronApplication,type Page} from '@playwright/test';
/** Electron exposes embedded WebContentsViews as pages too. Select the shell
 * by its trusted URL rather than whichever page wins the startup race. */
export async function shellWindow(app:ElectronApplication):Promise<Page>{
 await expect.poll(()=>app.windows().some(page=>page.url()==='app://shell/index.html'),{timeout:15000}).toBe(true);
 return app.windows().find(page=>page.url()==='app://shell/index.html')!;
}
