import { BrowserWindow, session } from 'electron';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { AssetRecord } from '../shared/assets';
import type { ConversionOptions, ConversionResult } from '../shared/workbench';
import type { RendererConversion, RendererConversionResult } from '../shared/converter';
import { csvToJSON, jsonToCSV, markdownHTML } from '../shared/formats';
import { AssetStore } from './asset-store';
import { AssetRuntime } from './asset-runtime';
import { localRendererResponse } from './local-renderer';
import { conversionFormats } from '../shared/conversion-formats';

type Running = { profileId:string; ids:string[]; cancelled:boolean; window?:BrowserWindow };
type Options = { rendererRoot:string;profileAvailable:(id:string)=>boolean;changed:(profileId:string)=>void };
const formats = new Set(['PNG','JPEG','WEBP','PDF','HTML','JSON','CSV']);
const mime:Record<string,string>={PNG:'image/png',JPEG:'image/jpeg',WEBP:'image/webp',PDF:'application/pdf',HTML:'text/html',JSON:'application/json',CSV:'text/csv'};
const ext = (name:string) => path.extname(name).toLowerCase();
class ConversionFailure extends Error { constructor(message:string,readonly completed:AssetRecord[]){super(message);} }

export class AssetConversions {
  private readonly running=new Set<Running>();
  constructor(private readonly store:AssetStore,private readonly options:Options){}
  private active(job:Running):void { if(job.cancelled||!this.options.profileAvailable(job.profileId))throw new Error('转换已取消，原文件保留。'); }
  private async timed<T>(job:Running,promise:Promise<T>,ms=45_000):Promise<T>{
    let timer:ReturnType<typeof setTimeout>|undefined;
    try{return await Promise.race([promise,new Promise<T>((_resolve,reject)=>{timer=setTimeout(()=>{reject(new Error('本地转换超时，原件已保留，请检查文件内容或缩小范围后重试。'));if(job.window&&!job.window.isDestroyed())job.window.destroy();},ms);})]);}
    finally{clearTimeout(timer);}
  }
  cancel(profileId:string,assetId:string):void {
    this.store.get(profileId,assetId);const job=[...this.running].find(j=>j.profileId===profileId&&j.ids.includes(assetId));
    if(!job)throw new Error('转换任务已经结束');job.cancelled=true;
    for(const id of job.ids)this.store.fail(profileId,id,'cancelled','转换已取消，原始文件保留。');
    if(job.window&&!job.window.isDestroyed())job.window.destroy();this.options.changed(profileId);
  }
  cancelProfile(profileId:string):void {for(const job of this.running)if(job.profileId===profileId){if(job.ids.length)this.cancel(profileId,job.ids[0]);else job.cancelled=true;}}
  dispose():void {for(const job of this.running){if(job.ids.length)this.cancel(job.profileId,job.ids[0]);else job.cancelled=true;}}
  private validate(value:ConversionOptions):void {
    if(!value||!formats.has(value.format))throw new Error('转换格式无效');
    if(value.width!==undefined&&(!Number.isSafeInteger(value.width)||value.width<16||value.width>8192))throw new Error('输出宽度应为 16–8192 像素');
    if(value.quality!==undefined&&(!Number.isFinite(value.quality)||value.quality<0.1||value.quality>1))throw new Error('图片质量应为 10%–100%');
    if(value.pages!==undefined&&(!Array.isArray(value.pages)||!value.pages.length||value.pages.length>20||new Set(value.pages).size!==value.pages.length||value.pages.some(p=>!Number.isSafeInteger(p)||p<1)))throw new Error('一次请选择 1–20 个不同的 PDF 页码');
    if(value.name!==undefined&&(typeof value.name!=='string'||!value.name.trim()||value.name.length>160||/[\\/\u0000-\u001f\u007f]/.test(value.name)))throw new Error('输出名称无效');
  }
  async convert(profileId:string,ids:string[],value:ConversionOptions):Promise<ConversionResult>{
    this.validate(value);if(!Array.isArray(ids)||!ids.length||ids.length>20||new Set(ids).size!==ids.length)throw new Error('一次请选择 1–20 个不同的源文件');
    if(this.running.size>=2)throw new Error('已有本地转换进行中，请等待当前任务完成。');
    const sources=ids.map(id=>this.store.get(profileId,id));
    if(sources.some(a=>a.status!=='ready'||a.trashedAt!==undefined||!conversionFormats(a).includes(value.format)))throw new Error('所选文件尚未就绪，或没有共同的转换格式');
    const result:ConversionResult={assets:[],failures:[]};
    for(const source of sources){
      try{const completed=await this.one(profileId,source,value,sources.length===1);result.assets.push(...completed);}
      catch(error){if(error instanceof ConversionFailure)result.assets.push(...error.completed);result.failures.push({name:source.name,error:error instanceof Error?error.message:'转换失败，原件保留。'});}
    }
    return result;
  }
  private async one(profileId:string,source:AssetRecord,value:ConversionOptions,single:boolean):Promise<AssetRecord[]>{
    const job:Running={profileId,ids:[],cancelled:false};this.running.add(job);
    let privateSession:Electron.Session|undefined;const completed:AssetRecord[]=[];
    const createResult=(page?:number)=>{
      const base=single&&value.name?value.name:source.name.replace(/\.[^.]+$/,'');const suffix=value.format==='JPEG'?'jpg':value.format.toLowerCase();
      const record=this.store.reserve(profileId,`${base}${page!==undefined?'-p'+page:''}.${suffix}`,mime[value.format],{kind:'conversion',profileName:source.source.profileName,fromAssetId:source.id,conversionFormat:value.format,title:source.source.title,conversationURL:source.source.conversationURL});
      job.ids.push(record.id);this.options.changed(profileId);return record;
    };
    const save=async(record:AssetRecord,bytes:Uint8Array|string)=>{this.active(job);await this.store.verifyFile(profileId,source.id);await writeFile(this.store.pendingPath(profileId,record.id),bytes,{flag:'wx',mode:0o600});this.active(job);const ready=await this.store.complete(profileId,record.id);completed.push(ready);this.options.changed(profileId);};
    try{
      this.active(job);await this.store.verifyFile(profileId,source.id);this.active(job);
      if(value.format==='JSON'||value.format==='CSV'||value.format==='HTML'){
        const record=createResult();const runtime=new AssetRuntime(this.store,()=>profileId);const text=runtime.text(profileId,source.id);
        const output=value.format==='JSON'?csvToJSON(text):value.format==='CSV'?jsonToCSV(text):markdownHTML(text);await save(record,output);return completed;
      }
      const record=createResult(ext(source.fileName)==='.pdf'?(value.pages?.[0]||1):undefined);
      privateSession=session.fromPartition('asset-conversion-'+randomUUID(),{cache:false});
      privateSession.setPermissionCheckHandler(()=>false);privateSession.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false));
      privateSession.on('will-download',event=>event.preventDefault());
      privateSession.webRequest.onBeforeRequest((request,callback)=>callback({cancel:!['app:','asset:','data:','blob:'].includes(new URL(request.url).protocol)}));
      const runtime=new AssetRuntime(this.store,()=>profileId);runtime.register(privateSession.protocol);privateSession.protocol.handle('app',request=>localRendererResponse(this.options.rendererRoot,request));
      const win=new BrowserWindow({show:false,width:value.width||1280,height:900,skipTaskbar:true,focusable:false,webPreferences:{session:privateSession,sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true,allowRunningInsecureContent:false,backgroundThrottling:false}});job.window=win;
      win.webContents.setWindowOpenHandler(()=>({action:'deny'}));
      const sourcePreview=await runtime.preview(profileId,source.id);
      if(source.kind==='image'||ext(source.fileName)==='.pdf'){
        await this.timed(job,win.loadURL('app://shell/converter.html'));
        if(ext(source.fileName)==='.pdf'){
          const info=await this.timed(job,win.webContents.executeJavaScript(`window.assetPDFInfo(${JSON.stringify(sourcePreview.url)})`)) as {pages:number};
          const pages=value.pages||[1];if(pages.some(page=>page>info.pages))throw new Error(`页码超出范围，此 PDF 共 ${info.pages} 页。`);
          for(const [index,page] of pages.entries()){
            this.active(job);const target=index===0?record:createResult(page);
            const request:RendererConversion={mode:'pdf',url:sourcePreview.url!,format:value.format as 'PNG'|'JPEG',page,width:value.width,quality:value.quality};
            const output=await this.timed(job,win.webContents.executeJavaScript(`window.assetConvert(${JSON.stringify(request)})`)) as RendererConversionResult;
            if(!output.data.startsWith(`data:${mime[value.format]};base64,`))throw new Error('图像转换返回格式不一致');await save(target,Buffer.from(output.data.split(',',2)[1],'base64'));
          }
        }else{
          const request:RendererConversion={mode:'image',url:sourcePreview.url!,format:value.format as 'PNG'|'JPEG'|'WEBP',width:value.width,quality:value.quality};
          const output=await this.timed(job,win.webContents.executeJavaScript(`window.assetConvert(${JSON.stringify(request)})`)) as RendererConversionResult;
          if(!output.data.startsWith(`data:${mime[value.format]};base64,`))throw new Error('图像转换返回格式不一致');await save(record,Buffer.from(output.data.split(',',2)[1],'base64'));
        }
      }else{
        if(ext(source.fileName)==='.md'){const html=markdownHTML(runtime.text(profileId,source.id));await this.timed(job,win.loadURL('data:text/html;charset=utf-8,'+encodeURIComponent(html)));}
        else await this.timed(job,win.loadURL(sourcePreview.url!));
        await this.timed(job,win.webContents.executeJavaScript("Promise.race([Promise.all([document.fonts.ready,...Array.from(document.images).map(image=>image.complete?Promise.resolve():new Promise(resolve=>{image.onload=image.onerror=()=>resolve();}))]).then(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))),new Promise(resolve=>setTimeout(resolve,4000))])"));
        this.active(job);
        if(value.format==='PDF'){const bytes=await this.timed(job,win.webContents.printToPDF({printBackground:true,pageSize:'A4',preferCSSPageSize:true}));await save(record,bytes);}
        else{
          win.webContents.debugger.attach('1.3');const metrics=await win.webContents.debugger.sendCommand('Page.getLayoutMetrics');const dimensions=metrics.cssContentSize||metrics.contentSize;
          const width=value.width||1280,height=Math.ceil(dimensions.height);if(!Number.isFinite(height)||height<1||height>16384||width*height>40_000_000)throw new Error('页面过长，建议导出 PDF 或缩小内容后再生成图片。');
          const screenshot=await this.timed(job,win.webContents.debugger.sendCommand('Page.captureScreenshot',{format:'png',captureBeyondViewport:true,clip:{x:0,y:0,width,height,scale:1}}));await save(record,Buffer.from(screenshot.data,'base64'));
        }
      }
      return completed;
    }catch(error){
      for(const id of job.ids)if(this.store.get(profileId,id).status!=='ready')this.store.fail(profileId,id,job.cancelled?'cancelled':'failed',job.cancelled?'转换已取消，原件已保留。':error instanceof Error?error.message:'本地转换失败，原件已保留。');
      this.options.changed(profileId);throw new ConversionFailure(error instanceof Error?error.message:'转换失败，原件保留。',completed);
    }finally{
      if(job.window&&!job.window.isDestroyed())job.window.destroy();
      if(privateSession){privateSession.webRequest.onBeforeRequest(null);privateSession.protocol.unhandle('asset');privateSession.protocol.unhandle('app');await privateSession.clearStorageData().catch(()=>{});await privateSession.clearCache().catch(()=>{});}
      this.running.delete(job);
    }
  }
}
