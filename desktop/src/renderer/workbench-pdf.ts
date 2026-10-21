import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist';
import workerURL from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
GlobalWorkerOptions.workerSrc=workerURL;

export async function mountPDF(root:HTMLElement,url:string):Promise<()=>void> {
  let disposed=false,document:PDFDocumentProxy|undefined,rendering:RenderTask|undefined,page=1;
  const loading=getDocument({url,enableXfa:false,cMapUrl:'app://shell/pdfjs/cmaps/',cMapPacked:true,standardFontDataUrl:'app://shell/pdfjs/standard_fonts/',wasmUrl:'app://shell/pdfjs/wasm/'});
  try {
    document=await loading.promise;if(!root.isConnected){await loading.destroy();return()=>{};}
    root.style.display='block';root.innerHTML='<div class="aw-pdf-toolbar"><button type="button" data-prev>上一页</button><span data-pages></span><button type="button" data-next>下一页</button></div><canvas class="aw-pdf-canvas"></canvas>';
    const canvas=root.querySelector('canvas')!,context=canvas.getContext('2d')!;
    const prev=root.querySelector<HTMLButtonElement>('[data-prev]')!,next=root.querySelector<HTMLButtonElement>('[data-next]')!,label=root.querySelector('[data-pages]')!;
    let drawVersion=0;
    const draw=async()=>{if(disposed||!document)return;const version=++drawVersion,previous=rendering;previous?.cancel();if(previous)await previous.promise.catch(()=>{});const target=page,pdfPage=await document.getPage(target);if(disposed||target!==page||version!==drawVersion)return;const viewport=pdfPage.getViewport({scale:1.5});canvas.width=Math.ceil(viewport.width);canvas.height=Math.ceil(viewport.height);label.textContent=`第 ${page} 页 / ${document.numPages} 页`;prev.disabled=page===1;next.disabled=page===document.numPages;rendering=pdfPage.render({canvas,canvasContext:context,viewport});try{await rendering.promise;}catch(error){if(!disposed&&target===page&&version===drawVersion)throw error;}};
    prev.addEventListener('click',()=>{if(page>1){page--;void draw();}});next.addEventListener('click',()=>{if(document&&page<document.numPages){page++;void draw();}});await draw();
    const cleanup=()=>{if(disposed)return;disposed=true;rendering?.cancel();void loading.destroy();};
    const observer=new MutationObserver(()=>{if(!root.isConnected){cleanup();observer.disconnect();}});observer.observe(window.document.body,{childList:true,subtree:true});
    return()=>{observer.disconnect();cleanup();};
  } catch(error) {void loading.destroy();throw error;}
}
