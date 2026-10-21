import{BrowserWindow,Menu,dialog,type WebContents,type ContextMenuParams}from'electron';
import{diagnosticOrigin}from'./login-diagnostics';
import{isWebURL}from'./policy';
type Host={window:()=>BrowserWindow|undefined;notice:(message:string)=>void;fixtureOrigin?:string};
function imageURL(url:string,frameURL:string,fixtureOrigin?:string):boolean{
 if(isWebURL(url))return true;
 try{const u=new URL(url);if(fixtureOrigin&&u.origin===fixtureOrigin)return true;if(u.protocol==='blob:')return u.origin===new URL(frameURL).origin;return /^data:image\/(png|jpeg|gif|webp|avif|svg\+xml)[;,]/i.test(url);}catch{return false;}
}
function imageScript(params:ContextMenuParams,action:'info'|'retry'):string{
 // Long embedded data URLs are not copied into an injected script.
 const wanted=params.srcURL.length<=65536?params.srcURL:null;
 return `(()=>{const wanted=${JSON.stringify(wanted)},point=document.elementFromPoint(${params.x},${params.y});const img=wanted?[...document.images].find(i=>i.currentSrc===wanted||i.src===wanted):point instanceof HTMLImageElement?point:null;
 ${action==='retry'?`if(!img)throw Error('图片元素已改变');img.fetchPriority='high';img.loading='eager';const source=img.getAttribute('src');if(source)img.setAttribute('src',source);return img.decode().then(()=>true);`:`const entry=wanted?performance.getEntriesByName(wanted).filter(e=>e.entryType==='resource').at(-1):undefined;return{loaded:!!img?.complete&&!!img.naturalWidth,width:img?.naturalWidth||0,height:img?.naturalHeight||0,displayWidth:Math.round(img?.getBoundingClientRect().width||0),displayHeight:Math.round(img?.getBoundingClientRect().height||0),duration:entry?.duration||0,transfer:entry?.transferSize||0,encoded:entry?.encodedBodySize||0,wait:entry?.requestStart>0&&entry?.responseStart>0?entry.responseStart-entry.requestStart:null,status:entry?.responseStatus||0};`}})()`;
}
export function installImageContextMenu(contents:WebContents,host:Host):void{
 contents.on('context-menu',(_event,params)=>{
  if(!['image','canvas'].includes(params.mediaType))return;const frame=params.frame;if(!frame)return;const frameURL=frame.url,source=params.srcURL;
  const alive=()=>{if(contents.isDestroyed()||frame.url!==frameURL)throw Error('页面已改变，请重新右键图片。');};
  const run=(action:()=>void|Promise<void>)=>()=>{try{alive();void Promise.resolve(action()).catch(()=>host.notice('图片操作未完成，请等待图片加载后重试。'));}catch{host.notice('图片所在页面已改变，请重新右键。');}};
  const execute=<T>(code:string)=>frame.executeJavaScript(code,true) as Promise<T>;
  const save=imageURL(source,frameURL,host.fixtureOrigin);
  const menu=Menu.buildFromTemplate([
   {id:'image-copy',label:'复制图片',enabled:params.hasImageContents,click:run(()=>contents.copyImageAt(params.x,params.y))},
   {id:'image-save',label:'保存图片到资产库',enabled:!!source&&save,click:run(()=>contents.downloadURL(source))},
   {type:'separator'},
   {id:'image-retry',label:'优先重试加载此图片',enabled:params.mediaType==='image',click:run(async()=>{host.notice('正在优先加载这张图片…');await execute(imageScript(params,'retry'));host.notice('这张图片已完成加载，可右键复制。');})},
   {id:'image-info',label:'查看图片加载信息',click:run(async()=>{
    const info=await execute<{loaded:boolean;width:number;height:number;displayWidth:number;displayHeight:number;duration:number;transfer:number;encoded:number;wait:number|null;status:number}>(imageScript(params,'info'));
    const transfer=info.transfer>0?`${(info.transfer/1024).toFixed(1)} KiB`:info.encoded>0?'浏览器本地缓存':'未公开（跨域或内存图片）';
    const detail=[`来源：${diagnosticOrigin(source)}`,`状态：${info.loaded?'已加载':'尚未完成加载'}${info.status?' · HTTP '+info.status:''}`,`原始像素：${info.width} × ${info.height}`,`页面显示：${info.displayWidth} × ${info.displayHeight}`,`资源耗时：${info.duration>0?Math.round(info.duration)+' ms':'未公开 / 内存图片'}`,`服务器等待：${info.wait!==null?Math.round(info.wait)+' ms':'未公开'}`,`传输：${transfer}`,'','跨域资源可能不公开部分计时；这些值不包含网站后端生成图片的时间。'].join('\n');
    const window=BrowserWindow.fromWebContents(contents)||host.window();if(window)await dialog.showMessageBox(window,{type:'info',title:'图片加载信息',message:'当前图片',detail,buttons:['关闭']});
   })}
  ]);const window=BrowserWindow.fromWebContents(contents)||host.window();if(window)menu.popup({window});
 });
}
