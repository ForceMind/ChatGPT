export type RendererConversion = { mode:'image'|'pdf';url:string;format:'PNG'|'JPEG'|'WEBP';width?:number;quality?:number;page?:number };
export type RendererConversionResult = { data:string;mime:string;width:number;height:number };
export type ConverterWindow = Window & {
  assetConvert:(input:RendererConversion)=>Promise<RendererConversionResult>;
  assetPDFInfo:(url:string)=>Promise<{pages:number}>;
};
