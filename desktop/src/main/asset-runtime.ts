import { protocol } from 'electron';
import { randomUUID } from 'node:crypto';
import { constants, createReadStream, lstatSync, readFileSync, realpathSync, openSync, readSync, closeSync } from 'node:fs';
import { Readable } from 'node:stream';
import path from 'node:path';
import type { AssetRecord } from '../shared/assets';
import type { AssetItem, AssetPreview } from '../shared/workbench';
import { AssetStore } from './asset-store';

const mimeByExtension: Record<string,string> = { '.html':'text/html','.htm':'text/html','.md':'text/markdown','.txt':'text/plain','.json':'application/json','.csv':'text/csv','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.webp':'image/webp','.gif':'image/gif','.avif':'image/avif','.bmp':'image/bmp','.ico':'image/x-icon','.pdf':'application/pdf','.mp3':'audio/mpeg','.wav':'audio/wav','.ogg':'audio/ogg','.m4a':'audio/mp4','.flac':'audio/flac','.mp4':'video/mp4','.m4v':'video/mp4','.webm':'video/webm','.mov':'video/quicktime' };
const textExtensions=new Set(['.md','.txt','.json','.csv','.log','.js','.mjs','.cjs','.ts','.tsx','.jsx','.css','.scss','.less','.xml','.yaml','.yml','.toml','.ini','.conf','.sql','.py','.java','.kt','.swift','.c','.cpp','.h','.rs','.go','.sh','.ipynb','.srt','.vtt']);
export function systemOpenAllowed(name:string):boolean{return !/\.(exe|com|msi|msix|bat|cmd|command|ps1|sh|app|apk|aab|dmg|pkg|desktop|lnk|url|webloc|scr|vbs|js|mjs|cjs|jar|workflow|scpt|applescript|py|pyw|rb|pl|php|lua|r|ahk|vbe|wsf|wsh|jse|hta|sct|reg|cpl)$/i.test(name);}
export const assetMime = (name: string, fallback = 'application/octet-stream'): string => mimeByExtension[path.extname(name).toLowerCase()] || fallback;
function responseType(file:string,mime:string):string {
  if(mime!=='text/html')return mime;
  const fd=openSync(file,constants.O_RDONLY|(constants.O_NOFOLLOW||0));
  try{const bytes=Buffer.alloc(16384);const count=readSync(fd,bytes,0,bytes.length,0);const head=bytes.subarray(0,count);if(/charset\s*=/i.test(head.toString('latin1')))return mime;
    try{new TextDecoder('utf-8',{fatal:true}).decode(head,{stream:count===bytes.length});return mime+'; charset=utf-8';}catch{return mime;}
  }finally{closeSync(fd);}
}
type Grant = { profileId: string; assetId: string; mode: 'raw' | 'html' | 'html-online'; expires: number };
const htmlCSP = "default-src 'none'; script-src 'unsafe-inline' asset:; style-src 'unsafe-inline' asset:; img-src asset: data: blob:; font-src asset: data:; media-src asset: data: blob:; connect-src asset:; frame-src 'none'; object-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'";
const rawCSP = "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src asset: data:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";

/** Capability URLs are only registered in the local shell, never a ChatGPT account session. */
export class AssetRuntime {
  private readonly grants = new Map<string, Grant>();
  private readonly urls = new Map<string, string>();
  constructor(readonly store: AssetStore, private readonly activeProfile: () => string, private readonly testOnlineOrigin?:string) {}
  register(target:Electron.Protocol=protocol): void { target.handle('asset', request => this.response(request)); }
  revoke(profileId?:string): void { if(!profileId){this.grants.clear();this.urls.clear();return;}for(const [token,grant] of this.grants)if(grant.profileId===profileId)this.grants.delete(token);for(const key of this.urls.keys())if(key.startsWith(profileId+':'))this.urls.delete(key); }
  private url(asset: AssetRecord, mode: Grant['mode']): string {
    const key = `${asset.profileId}:${asset.id}:${mode}`;
    const existing = this.urls.get(key);
    if (existing) { const token = new URL(existing).hostname; const grant = this.grants.get(token); if (grant && grant.expires > Date.now()) return existing; this.grants.delete(token); }
    const token = randomUUID();
    this.grants.set(token, { profileId: asset.profileId, assetId: asset.id, mode, expires: Date.now() + 30*60_000 });
    const result = `asset://${token}/${encodeURIComponent(asset.fileName)}`; this.urls.set(key, result); return result;
  }
  items(profileId: string): AssetItem[] {
    return this.store.list(profileId).map(asset => ({ ...asset, ...(asset.status === 'ready' && !asset.trashedAt && asset.kind === 'image' ? {thumbnailURL:this.url(asset,'raw')} : {}) }));
  }
  async preview(profileId: string, id: string, options?:{online?:boolean}): Promise<AssetPreview> {
    await this.store.verifyFile(profileId,id); const asset = this.store.get(profileId,id);
    if (asset.kind === 'image') return {assetId:id,mode:'image',url:this.url(asset,'raw')};
    if (asset.kind === 'web') {
      const file=this.store.resolveFile(profileId,id),fd=openSync(file,constants.O_RDONLY|(constants.O_NOFOLLOW||0));let head='';
      try{const bytes=Buffer.alloc(Math.min(128*1024,lstatSync(file).size));head=bytes.subarray(0,readSync(fd,bytes,0,bytes.length,0)).toString('utf8');}finally{closeSync(fd);}
      const external=/(?:src|href)\s*=\s*["']\s*(?:https?:)?\/\//i.test(head)||/\b(?:fetch|import)\s*\(\s*["']https?:/i.test(head);
      return {assetId:id,mode:'html',url:this.url(asset,options?.online?'html-online':'html'),online:!!options?.online,...(external&&!options?.online?{warning:'此 HTML 引用了联网资源，离线预览可能显示不完整。可允许此文件加载联网资源，或使用系统应用打开。'}:{})};
    }
    if (path.extname(asset.fileName).toLowerCase() === '.pdf') return {assetId:id,mode:'pdf',url:this.url(asset,'raw')};
    const type=assetMime(asset.fileName,asset.mime);
    if(asset.kind==='audio'||type.startsWith('audio/'))return {assetId:id,mode:'audio',url:this.url(asset,'raw')};
    if(asset.kind==='video'||type.startsWith('video/'))return {assetId:id,mode:'video',url:this.url(asset,'raw')};
    if (['data','document'].includes(asset.kind)||textExtensions.has(path.extname(asset.fileName).toLowerCase())||type.startsWith('text/')) {
      const ext = path.extname(asset.fileName).toLowerCase();
      if (['.doc','.docx','.xls','.xlsx','.ppt','.pptx','.rtf'].includes(ext)) return {assetId:id,mode:'unsupported'};
      try{return {assetId:id,mode:ext==='.csv'?'table':ext==='.md'?'markdown':'text',text:this.text(profileId,id)};}
      catch(error){return {assetId:id,mode:'unsupported',warning:error instanceof Error?error.message:'无法读取文本，可使用系统应用打开。'};}
    }
    return {assetId:id,mode:'unsupported'};
  }
  text(profileId: string, id: string): string {
    const asset = this.store.get(profileId,id);
    const file = this.store.resolveFile(profileId,id);
    if (!['web','document','data'].includes(asset.kind) && path.extname(asset.fileName).toLowerCase() !== '.svg'&&!textExtensions.has(path.extname(asset.fileName).toLowerCase())&&!asset.mime.startsWith('text/')) throw new Error('此文件不支持文本预览');
    if (lstatSync(file).size > 2*1024*1024) throw new Error('文本预览上限为 2 MB，可导出文件或交给 GPT 处理。');
    const bytes = readFileSync(file);
    try {
      if (bytes[0]===0xff && bytes[1]===0xfe) return new TextDecoder('utf-16le',{fatal:true}).decode(bytes);
      if (bytes[0]===0xfe && bytes[1]===0xff) return new TextDecoder('utf-16be',{fatal:true}).decode(bytes);
      return new TextDecoder('utf-8',{fatal:true}).decode(bytes);
    } catch { throw new Error('无法按 UTF-8 / UTF-16 读取，请使用原文件或转成 UTF-8 后导入。'); }
  }
  private async response(request: Request): Promise<Response> {
    try {
      const url = new URL(request.url); const grant = this.grants.get(url.hostname);
      if (!grant || grant.expires < Date.now() || grant.profileId !== this.activeProfile() || !['GET','HEAD'].includes(request.method)) return new Response('Forbidden',{status:403});
      const asset = this.store.get(grant.profileId,grant.assetId);
      if (decodeURIComponent(url.pathname) !== '/' + asset.fileName || url.search) return new Response('Not found',{status:404});
      const file = this.store.resolveFile(grant.profileId,grant.assetId);
      if (!realpathSync(file).startsWith(this.store.filesDirectory+path.sep)) return new Response('Forbidden',{status:403});
      const info = lstatSync(file); let start = 0, end = info.size-1; let partial = false;
      const range = request.headers.get('range');
      if (range) {
        const match = /^bytes=(\d*)-(\d*)$/.exec(range);
        if (!match || (!match[1]&&!match[2])) return new Response(null,{status:416,headers:{'Content-Range':`bytes */${info.size}`}});
        if (!match[1]) start = Math.max(0,info.size-Number(match[2]));
        else { start = Number(match[1]); if(match[2]) end=Math.min(end,Number(match[2])); }
        if (!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start<0||start>end||start>=info.size) return new Response(null,{status:416,headers:{'Content-Range':`bytes */${info.size}`}});
        partial = true;
      }
      const testOrigin=this.testOnlineOrigin&&/^http:\/\/127\.0\.0\.1:\d+$/.test(this.testOnlineOrigin)?' '+this.testOnlineOrigin:'';
      const onlineCSP="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' asset: https:"+testOrigin+"; style-src 'unsafe-inline' asset: https:"+testOrigin+"; img-src asset: https: data: blob:"+testOrigin+"; font-src asset: https: data:"+testOrigin+"; media-src asset: https: data: blob:"+testOrigin+"; connect-src asset: https:"+testOrigin+"; frame-src 'none'; object-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'";
      const headers: Record<string,string> = {'Content-Type':responseType(file,assetMime(asset.fileName,asset.mime)),'Cache-Control':'no-store','Content-Security-Policy':grant.mode==='html'?htmlCSP:grant.mode==='html-online'?onlineCSP:rawCSP,'X-Content-Type-Options':'nosniff','Access-Control-Allow-Origin':'*','Accept-Ranges':'bytes','Content-Length':String(Math.max(0,end-start+1))};
      if(partial)headers['Content-Range']=`bytes ${start}-${end}/${info.size}`;
      const stream = request.method==='HEAD'||info.size===0?null:Readable.toWeb(createReadStream(file,{start,end,fd:openSync(file,constants.O_RDONLY | (constants.O_NOFOLLOW || 0)),autoClose:true}));
      return new Response(stream as ReadableStream<Uint8Array> | null,{status:partial?206:200,headers});
    } catch { return new Response('Asset unavailable',{status:404}); }
  }
}
