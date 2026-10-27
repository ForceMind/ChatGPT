import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const files={'/':['index.html','text/html; charset=utf-8'],'/app.js':['app.js','text/javascript; charset=utf-8'],'/styles.css':['styles.css','text/css; charset=utf-8'],'/alpha22.html':['alpha22.html','text/html; charset=utf-8'],'/alpha22.css':['alpha22.css','text/css; charset=utf-8']};
const port=Number(process.env.STUDIO_PREVIEW_PORT||4187);
createServer(async(req,res)=>{const file=files[new URL(req.url,'http://127.0.0.1').pathname];if(!file){res.writeHead(404).end();return;}try{const bytes=await readFile(new URL(file[0],import.meta.url));res.writeHead(200,{'Content-Type':file[1],'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}).end(bytes);}catch{res.writeHead(500).end();}}).listen(port,'127.0.0.1',()=>console.log('Android interaction preview: http://127.0.0.1:'+port+'/ ('+fileURLToPath(new URL('.',import.meta.url))+')'));
