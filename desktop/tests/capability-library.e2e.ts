import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,rmSync} from 'node:fs';
import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';
import {shellWindow} from './helpers/shell';
import {CapabilityCatalog} from '../src/main/capabilities';
import type {HandoffJob} from '../src/shared/handoff';
const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));

test('original role library keeps internet priority, runs an agent-only task once and restores favorites and selection',async({},info)=>{
 test.skip(!['darwin','win32'].includes(process.platform));
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-role-ui-')),messages:string[]=[];let app:ElectronApplication|undefined;
 const server=createServer(async(q,r)=>{
  if(q.url==='/message'){let body='';for await(const chunk of q)body+=chunk.toString();messages.push(JSON.parse(body).prompt);r.writeHead(200,{'Content-Type':'application/json'});r.end('{"ok":true}');return;}
  r.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});r.end(`<!doctype html><meta charset="utf-8"><title>Local role task fixture</title><h1>本地角色任务验收</h1><textarea id="prompt-textarea"></textarea><button data-testid="send-button" disabled>Send</button><section id="messages"></section><script>
   const text=document.querySelector('textarea'),send=document.querySelector('button');text.addEventListener('input',()=>send.disabled=!text.value.trim());
   send.addEventListener('click',async()=>{if(send.disabled)return;send.disabled=true;const value=text.value;await fetch('/message',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:value})});const message=document.createElement('div');message.dataset.messageAuthorRole='user';message.textContent=value;document.querySelector('#messages').append(message);text.value='';history.pushState({},'','/c/role-fixture');});
  </script>`);
 });
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));const address=server.address();if(!address||typeof address==='string')throw Error('fixture');
 const origin='http://127.0.0.1:'+address.port,catalog=new CapabilityCatalog(path.join(desktop,'resources/capabilities')),role=catalog.agent('studio-agent-025');
 try{
  const launch=async()=>{app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:origin}});return shellWindow(app,{width:1360,height:900});};
  let shell=await launch();const initial=await shell.evaluate(()=>window.profiles.snapshot());
  await shell.getByRole('button',{name:'能力库',exact:true}).click();await shell.locator('#wb-agents-nav').click();
  const grid=shell.locator('#wb-capability-grid'),cards=grid.locator('[data-capability]');await expect(cards).toHaveCount(32);
  await expect(shell.locator('#wb-agents-nav')).toContainText('32');await expect(shell.locator('#wb-skills-nav')).toContainText('121');
  const priority=await cards.evaluateAll(items=>items.map(item=>item.getAttribute('data-priority')));expect(priority.slice(0,24)).toEqual(Array(24).fill('true'));expect(priority.slice(24)).toEqual(Array(8).fill('false'));
  await shell.locator('[data-capability-favorite="studio-agent-025"]').click();
  expect((await cards.first().getAttribute('data-priority'))).toBe('true');
  await shell.locator('[data-library-filter="internet"]').click();await expect(cards).toHaveCount(24);
  await shell.locator('[data-library-filter="all"]').click();await shell.locator('#wb-capability-search').fill('资料研究员');await expect(cards).toHaveCount(1);await cards.first().click();
  await shell.locator('#wb-capability-detail [data-action="agent-full"]').click();await expect(shell.locator('#wb-modal-root')).toContainText(role.instructions);await shell.locator('#wb-modal-root [data-action="close-modal"]').click();
  await shell.locator('#wb-capability-search').fill('');await shell.screenshot({path:info.outputPath('original-agent-library.png')});
  await shell.locator('[data-capability="studio-agent-025"]').click();await shell.locator('#wb-capability-detail [data-action="use-capability"]').click();
  await expect(shell.locator('#wb-task-agent')).toHaveValue(role.id);await expect(shell.locator('#wb-task-skill')).toHaveValue('');
  await shell.locator('#wb-task-instructions').fill('仅处理本次本地验收，缺少材料时说明缺少什么。');await shell.locator('[data-action="task-prompt"]').click();await expect(shell.locator('#wb-prompt-preview')).toContainText(role.instructions);
  await shell.locator('[data-action="start-handoff"]').click();const profile=initial.activeProfileId;
  await expect.poll(()=>shell.evaluate(id=>window.profiles.workbench.handoffs(id).then(j=>j[0]?.status),profile),{timeout:45000}).toBe('submitted');
  expect(messages).toHaveLength(1);expect(messages[0]).toContain(role.instructions);expect(messages[0]).toContain('本次未附文件');expect(messages[0]).not.toContain(root);
  const jobs:HandoffJob[]=await shell.evaluate(id=>window.profiles.workbench.handoffs(id),profile),job=jobs[0];expect(job.request.agentId).toBe(role.id);expect(job.catalogCommit).toBe('project-skills-v2');
  const repeated=await shell.evaluate(request=>window.profiles.workbench.startHandoff(request),job.request);expect(repeated.id).toBe(job.id);expect(repeated.status).toBe('submitted');expect(messages).toHaveLength(1);
  await shell.locator('#wb-modal-root [data-action="close-modal"]').click();await shell.locator('#wb-favorites-only').click();await expect(cards).toHaveCount(1);
  await expect.poll(()=>shell.evaluate(()=>window.profiles.snapshot().then(s=>s.profiles.find(p=>p.id===s.activeProfileId)?.workspace?.workbench?.librarySelected))).toBe(role.id);
  await app!.close();app=undefined;shell=await launch();
  await expect(shell.locator('#wb-capability-grid [data-capability]')).toHaveCount(1);await expect(shell.locator('[data-capability="studio-agent-025"]')).toBeVisible();
  const restored=await shell.evaluate(()=>window.profiles.snapshot());expect(restored.activeProfileId).toBe(profile);expect(restored.capabilityFavorites).toContain(role.id);expect(messages).toHaveLength(1);
  await shell.locator('#wb-favorites-only').click();await shell.locator('#wb-skills-nav').click();await shell.locator('#wb-capability-search').fill('DesignForge');await expect(shell.locator('#wb-capability-grid [data-capability]')).toHaveCount(1);
  await shell.locator('[data-capability="skill-121"]').click();await expect(shell.locator('#wb-capability-detail')).toContainText('baseHash');await shell.screenshot({path:info.outputPath('designforge-skill-adapter.png')});
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()));rmSync(root,{recursive:true,force:true});}
});
