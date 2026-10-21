import{shellWindow}from'./helpers/shell';
import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer,type Server} from 'node:http';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';
import {AssetStore} from '../src/main/asset-store';
import type {ConversionOptions} from '../src/shared/workbench';

const desktop=fileURLToPath(new URL('..',import.meta.url));
test('macOS conversion produces real image/PDF/HTML/data assets with original lineage',async({},info)=>{
  test.skip(process.platform!=='darwin','本轮仅验证 Mac 转换引擎');test.setTimeout(180_000);
  const root=mkdtempSync(path.join(os.tmpdir(),'profiles-conversions-')),data=path.join(root,'data');let app:ElectronApplication|undefined,server:Server|undefined;
  try{
    server=createServer((_request,response)=>{response.writeHead(200,{'Content-Type':'text/html'});response.end('<!doctype html><h1>Local conversion acceptance fixture</h1>');});await new Promise<void>(resolve=>server!.listen(0,'127.0.0.1',resolve));const address=server.address();if(!address||typeof address==='string')throw new Error('no fixture port');
    app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:data,PROFILES_FIXTURE_ORIGIN:`http://127.0.0.1:${address.port}`}});const shell=await shellWindow(app);await shell.waitForURL('app://shell/index.html');await expect(shell.locator('.current-account')).toBeVisible();
    const profile=(await shell.evaluate(()=>window.profiles.snapshot())).activeProfileId;
    const fixtures:Record<string,string>={
      'drawing.svg':'<svg xmlns="http://www.w3.org/2000/svg" width="600" height="400"><rect width="600" height="400" fill="#497b53"/><text x="50" y="150" fill="white">Conversion fixture</text></svg>',
      'page.html':'<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:sans-serif;padding:30px;color:#333}h1{font-size:30px}</style></head><body><h1>实际 HTML 转换</h1><p>Original content stays untouched.</p></body></html>',
      'notes.md':'# Markdown 转换\n\n**保留原件**\n\n- 清晰的结构\n- 新文件与来源关联',
      'data.csv':'名称,编号\n样例,00123\n第二项,1e2',
      'data.json':'[{"名称":"样例","数量":12},{"名称":"=1+1","数量":3}]'
    };
    const files=Object.keys(fixtures).map(name=>path.join(root,name));for(const [name,body]of Object.entries(fixtures))writeFileSync(path.join(root,name),body);
    await shell.evaluate(()=>{const input=document.createElement('input');input.type='file';input.multiple=true;input.id='convert-import';document.body.append(input);input.addEventListener('change',()=>{(window as Window&{importTask?:Promise<void>}).importTask=window.profiles.snapshot().then(s=>window.profiles.workbench.importDropped(s.activeProfileId,[...input.files!]));});});
    await shell.locator('#convert-import').setInputFiles(files);await shell.evaluate(()=>(window as Window&{importTask?:Promise<void>}).importTask);
    const assetList=()=>shell.evaluate(id=>window.profiles.workbench.assets(id),profile);const initial=await assetList();expect(initial).toHaveLength(5);
    const byName=(name:string)=>initial.find(a=>a.name===name)!;
    const convert=async(name:string,options:ConversionOptions)=>{const result=await shell.evaluate(({profile,id,options})=>window.profiles.workbench.convert(profile,[id],options),{profile,id:byName(name).id,options});expect(result.failures,JSON.stringify(result.failures)).toHaveLength(0);expect(result.assets.length).toBeGreaterThan(0);for(const asset of result.assets){expect(asset.status).toBe('ready');expect(asset.source.fromAssetId).toBe(byName(name).id);expect(asset.profileId).toBe(profile);}return result.assets;};
    const results:string[]=[];
    for(const format of ['PNG','JPEG','WEBP'] as const){const [out]=await convert('drawing.svg',{format,width:300,quality:0.85});const ledger=new AssetStore(data),buffer=readFileSync(ledger.resolveFile(profile,out.id));if(format==='PNG')expect(buffer.subarray(1,4).toString()).toBe('PNG');else if(format==='JPEG')expect(buffer.subarray(0,2)).toEqual(Buffer.from([255,216]));else expect(buffer.subarray(8,12).toString()).toBe('WEBP');const preview=(await assetList()).find(a=>a.id===out.id)!;const dimensions=await shell.evaluate(async url=>{const image=new Image();image.crossOrigin='anonymous';image.src=url;await image.decode();return{width:image.naturalWidth,height:image.naturalHeight};},preview.thumbnailURL!);expect(dimensions,format+' preview decoder').toEqual({width:300,height:200});results.push('SVG → '+format);}
    const [pdf]=await convert('page.html',{format:'PDF'});let ledger=new AssetStore(data);expect(readFileSync(ledger.resolveFile(profile,pdf.id)).subarray(0,5).toString()).toBe('%PDF-');results.push('HTML → PDF');
    const [capture]=await convert('page.html',{format:'PNG',width:800});ledger=new AssetStore(data);expect(readFileSync(ledger.resolveFile(profile,capture.id)).subarray(1,4).toString()).toBe('PNG');results.push('HTML → PNG');
    for(const format of ['HTML','PDF'] as const){const [out]=await convert('notes.md',{format});ledger=new AssetStore(data);const body=readFileSync(ledger.resolveFile(profile,out.id));expect(format==='HTML'?body.toString().includes('<h1>Markdown 转换</h1>'):body.subarray(0,5).toString()==='%PDF-').toBeTruthy();results.push('Markdown → '+format);}
    const [json]=await convert('data.csv',{format:'JSON'});ledger=new AssetStore(data);expect(JSON.parse(readFileSync(ledger.resolveFile(profile,json.id),'utf8'))[0]['编号']).toBe('00123');results.push('CSV → JSON');
    const [csv]=await convert('data.json',{format:'CSV'});ledger=new AssetStore(data);expect(readFileSync(ledger.resolveFile(profile,csv.id),'utf8')).toContain("'=1+1");results.push('JSON → CSV');
    const pdfResult=await shell.evaluate(({profile,id})=>window.profiles.workbench.convert(profile,[id],{format:'PNG',pages:[1],width:500}),{profile,id:pdf.id});expect(pdfResult.failures).toHaveLength(0);expect(pdfResult.assets).toHaveLength(1);results.push('PDF page → PNG');
    for(const [name,body]of Object.entries(fixtures))expect(readFileSync(path.join(root,name),'utf8')).toBe(body);
    await shell.getByRole('button',{name:'资产中心',exact:true}).click();await shell.locator('[data-filter="converted"]').click();await expect(shell.locator('#wb-asset-grid [data-status="ready"]')).toHaveCount(10);await shell.screenshot({path:info.outputPath('macos-real-conversions.png')});
    const preview=await shell.evaluate(({profile,id})=>window.profiles.workbench.preview(profile,id),{profile,id:pdf.id});expect(preview.mode).toBe('pdf');
    await shell.locator('[data-filter="all"]').click();await shell.locator('#wb-asset-search').fill(pdf.name);await shell.locator(`[data-asset="${pdf.id}"]`).click();await shell.locator('#wb-asset-detail [data-action="preview"]').first().click();await expect(shell.locator('#wb-viewer canvas')).toBeVisible();await expect(shell.locator('#wb-viewer [data-pages]')).toContainText('第 1 页');await shell.locator('#wb-modal-root [data-action="close-modal"]').click();results.push('real PDF canvas preview');
    await info.attach('conversion-results',{body:JSON.stringify({profile,checks:results,realAssets:10,originalsPreserved:true}),contentType:'application/json'});
  }finally{await app?.close().catch(()=>{});if(server)await new Promise<void>(resolve=>server!.close(()=>resolve()));rmSync(root,{recursive:true,force:true});}
});
