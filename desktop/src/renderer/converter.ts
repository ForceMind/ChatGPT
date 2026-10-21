import type { ConverterWindow, RendererConversion, RendererConversionResult } from '../shared/converter';

const formats:Record<string,string>={PNG:'image/png',JPEG:'image/jpeg',WEBP:'image/webp'};
function canvas(width:number,height:number):HTMLCanvasElement {
  if(!Number.isSafeInteger(width)||!Number.isSafeInteger(height)||width<1||height<1||width>16384||height>16384||width*height>40_000_000)throw new Error('图片尺寸过大，请选择较小的输出宽度。');
  const result=document.createElement('canvas');result.width=width;result.height=height;return result;
}
async function pdf(url:string){const {getDocument,GlobalWorkerOptions}=await import('pdfjs-dist');const {default:workerURL}=await import('pdfjs-dist/build/pdf.worker.min.mjs?url');GlobalWorkerOptions.workerSrc=workerURL;return getDocument({url,enableXfa:false,cMapUrl:'app://shell/pdfjs/cmaps/',cMapPacked:true,standardFontDataUrl:'app://shell/pdfjs/standard_fonts/',wasmUrl:'app://shell/pdfjs/wasm/'});}
const assetPDFInfo:ConverterWindow['assetPDFInfo']=async url=>{const loading=await pdf(url);try{const doc=await loading.promise;return{pages:doc.numPages};}finally{await loading.destroy();}};
const assetConvert=async(input:RendererConversion):Promise<RendererConversionResult>=>{
  const mime=formats[input.format];if(!mime)throw new Error('输出图片格式不支持');
  let result:HTMLCanvasElement;
  if(input.mode==='image'){
    const image=new Image();image.crossOrigin='anonymous';image.src=input.url;await image.decode();
    const width=input.width||image.naturalWidth,height=Math.max(1,Math.round(image.naturalHeight*width/image.naturalWidth));result=canvas(width,height);
    const context=result.getContext('2d')!;if(input.format==='JPEG'){context.fillStyle='#ffffff';context.fillRect(0,0,width,height);}context.drawImage(image,0,0,width,height);
  }else{
    const loading=await pdf(input.url);
    try{const doc=await loading.promise;const n=input.page||1;if(!Number.isInteger(n)||n<1||n>doc.numPages)throw new Error('PDF 页码超出范围');const page=await doc.getPage(n),base=page.getViewport({scale:1}),viewport=page.getViewport({scale:input.width?input.width/base.width:1.5});result=canvas(Math.ceil(viewport.width),Math.ceil(viewport.height));await page.render({canvas:result,canvasContext:result.getContext('2d')!,viewport}).promise;}finally{await loading.destroy();}
  }
  const data=result.toDataURL(mime,input.quality??0.92);if(!data.startsWith(`data:${mime};base64,`))throw new Error('当前图像引擎不支持所选输出格式');return{data,mime,width:result.width,height:result.height};
};
Object.assign(window,{assetPDFInfo,assetConvert});
