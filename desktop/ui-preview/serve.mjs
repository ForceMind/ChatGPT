import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('.',import.meta.url));
const server=http.createServer(async(request,response)=>{
  try{
    const url=new URL(request.url,'http://127.0.0.1');let name=decodeURIComponent(url.pathname);if(name==='/')name='/index.html';
    if(!/^\/(?:index\.html|styles\.css|app\.js|catalog\.js|readability(?:-typography)?\.(?:css|js)|assets\/[a-zA-Z0-9_-]+\.svg)$/.test(name)){response.writeHead(404);response.end();return;}
    const file=name==='/assets/menu-bar-template.svg'?path.resolve(root,'../assets/menu-bar-template.svg'):path.join(root,name);const data=await fs.readFile(file);
    response.writeHead(200,{'Content-Type':({'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml'})[path.extname(file)],'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});response.end(data);
  }catch{response.writeHead(404);response.end();}
});
server.listen(4188,'127.0.0.1',()=>process.stdout.write('UI readability preview: http://127.0.0.1:4188/\n'));
