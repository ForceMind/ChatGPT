import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

export function localRendererResponse(root:string,request:Request):Response {
  try {
    const url=new URL(request.url);if(url.host!=='shell')return new Response('Forbidden',{status:403});
    const file=path.resolve(root,'.'+decodeURIComponent(url.pathname));
    if(!file.startsWith(root+path.sep)||!existsSync(file))return new Response('Not found',{status:404});
    const mime:Record<string,string>={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.mjs':'text/javascript; charset=utf-8','.wasm':'application/wasm','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.woff':'font/woff','.woff2':'font/woff2'};
    return new Response(readFileSync(file),{headers:{'Content-Type':mime[path.extname(file)]||'application/octet-stream','Content-Security-Policy':"default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: asset:; font-src 'self' blob: data:; connect-src 'self' asset:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src asset: 'self'",'X-Content-Type-Options':'nosniff'}});
  }catch{return new Response('Not found',{status:404});}
}
