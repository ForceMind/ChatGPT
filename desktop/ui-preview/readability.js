/* Layout-only examples: no application bridge, account access, upload or update calls. */
(() => {
  const $ = (selector) => document.querySelector(selector);
  const dialog = $("#readability-dialog");
  let timer, toastTimer;
  const escape = (s) => String(s).replace(/[&<>\"]/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
  const toast = (message) => { const el=$("#toast");el.textContent=message;el.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.hidden=true,3200); };
  const intro = "可读性样式示例 · 不读取应用账号，不执行原生操作";
  const close = () => {clearInterval(timer);dialog.close();};
  const steps=[['校验文件与账号','保留原件，检查明确的跨账号授权'],['准备附件','文件名称、大小与来源可见'],['打开目标对话','明确显示目标账号'],['上传附件','上传进度单独展示'],['写入完整指令','核对全部指令，保留失败位置'],['等待发送就绪','按钮未就绪时说明原因'],['单次发送与确认','结果不明时停止，不自动重发']];
  const screens = {
    account: {title:"账号空间",description:intro,content:`<label for="ui-account-name">账号名称</label><input id="ui-account-name" value="个人账号" /><label for="ui-account-email">邮箱备注</label><input id="ui-account-email" value="personal@example.test" /><div class="readability-field-pair"><div><label for="ui-language">浏览器语言</label><select id="ui-language"><option>简体中文</option><option>English</option></select></div><div><label for="ui-timezone">时区</label><select id="ui-timezone"><option>Asia/Shanghai · UTC+08:00</option><option>America/Los_Angeles</option></select></div></div><p class="readability-info">备注帮助你区分空间，实际登录身份以 ChatGPT 网页为准。账号表单使用 15px 输入文字与 14px 标签。</p>`,primary:"保存样式示例"},
    progress: {title:"交给 GPT · 任务进度",description:"七个进度点 · 当前文件与目标账号始终可见",content:`<p class="readability-info"><strong>示例文件</strong> interactive-web-demo.html<br /><strong>目标账号</strong> 个人账号 · personal@example.test<br />这是模拟流程，不会创建对话、上传或发送。</p><ol class="readability-progress">${steps.map(([title,detail],i)=>`<li><b>${i+1}</b><div><strong>${title}</strong><small>${detail}</small></div></li>`).join('')}</ol>`,primary:"演示进度流转"},
    diagnostics: {title:"设置与诊断",description:"状态和值采用清楚的文字层次，错误不只靠浅色区分",content:`<div class="readability-diag-row"><span>界面字号</span><strong>正文 15px · 辅助 14px · 标签至少 13px</strong></div><div class="readability-diag-row"><span>当前浏览器空间</span><strong>个人账号 · 独立空间</strong></div><div class="readability-diag-row"><span>菜单栏入口</span><strong>待恢复至实际 App</strong></div><div class="readability-diag-row"><span>恢复记录</span><strong>示例状态：已保存</strong></div><div class="readability-diag-row"><span>不受支持的链接</span><strong>示例：webauthn: · 已停止打开</strong></div><p class="readability-info">系统诊断中的提示、时间和操作入口使用可读文字；不把正常可用操作处理成灰掉的禁用样式。</p>`,primary:"确认样式"},
    updates: {title:"发现新版本（演示）",description:"更新弹窗的标题、说明、进度与按钮统一可读",content:`<p>这是更新通知样式预览，不会检测、下载或安装软件。</p><p class="readability-info">更新内容、包大小与验证结果放在正文区。需要重启时明确说明，可选择稍后处理。</p><label for="ui-update-choice">安装时间</label><select id="ui-update-choice"><option>下载后再选择</option><option>下次退出时安装</option></select><button type="button" disabled>尚未下载，暂不可安装</button><p style="margin-top:12px;font-size:14px;color:#606060">禁用状态保留可读文字，并明确说明原因。</p>`,primary:"后台下载示例"},
    menu: {title:"Mac 顶部菜单栏",description:"拟恢复的原生菜单内容，实际菜单使用系统字号与深浅色图标",content:`<div class="readability-native-menu"><p>ChatGPT Studio · 个人账号</p><button data-ui-menu-action>显示 / 隐藏主窗口 <span>⌘</span></button><button data-ui-menu-action>新建对话</button><hr /><p>切换账号</p><button data-ui-menu-action>✓ 个人账号</button><button data-ui-menu-action>工作账号</button><hr /><button data-ui-menu-action>资产工作台</button><button data-ui-menu-action>Skill 能力库</button><button data-ui-menu-action>检查更新…</button><hr /><button data-ui-menu-action>退出 ChatGPT Studio</button></div><p class="readability-info" style="margin-top:20px">此处只是菜单样式示意。真正关闭窗口继续保留后台；选择退出时沿用保存草稿和退出更新流程。</p>`,primary:"知道了"}
  };
  function open(name){
    clearInterval(timer);const spec=screens[name];if(!spec)return;
    dialog.innerHTML=`<header class="readability-dialog-header"><div><h2 id="readability-dialog-title">${spec.title}</h2><p>${spec.description}</p></div><button class="readability-close" data-ui-close aria-label="关闭样式示例">×</button></header><section class="readability-dialog-content">${spec.content}</section><footer class="readability-dialog-footer"><button data-ui-close>关闭</button><button class="primary" data-ui-confirm="${name}">${spec.primary}</button></footer>`;
    dialog.showModal();
  }
  function mode(name){
    document.body.classList.toggle('readable-preview',name!=='original');document.body.dataset.uiScale=name==='large'?'large':'standard';
    document.querySelectorAll('[data-ui-mode]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.uiMode===name)));
    if(name==='original')document.body.classList.remove('detail-drawer-open');
  }
  function drawerButtons(){
    document.querySelectorAll('.asset-detail,.capability-detail').forEach(pane=>{
      const line=pane.querySelector('.detail-topline');if(line&&!line.querySelector('[data-ui-drawer-close]')){const b=document.createElement('button');b.className='detail-drawer-close icon-button';b.dataset.uiDrawerClose='';b.setAttribute('aria-label','关闭详情抽屉');b.textContent='×';line.appendChild(b);}
      if(pane.id==='asset-detail'){const actions=pane.querySelector('.detail-small-actions');if(actions&&!actions.querySelector('[data-ui-save]')){const b=document.createElement('button');b.dataset.uiSave='';b.textContent='另存为';actions.appendChild(b);}}
      if(!pane.querySelector(':scope>.readability-detail-body')){const line=pane.querySelector('.detail-topline'),actions=pane.querySelector('.detail-small-actions'),body=document.createElement('div');body.className='readability-detail-body';for(const child of [...pane.childNodes])if(child!==line&&child!==actions)body.appendChild(child);if(line)pane.appendChild(line);pane.appendChild(body);if(actions)pane.appendChild(actions);}
    });
  }
  for(const pane of document.querySelectorAll('.asset-detail,.capability-detail'))new MutationObserver(drawerButtons).observe(pane,{childList:true});
  drawerButtons();
  document.addEventListener('click',event=>{
    const b=event.target.closest('button');
    if(b?.dataset.uiMode)mode(b.dataset.uiMode);
    if(b?.dataset.uiOpen)open(b.dataset.uiOpen);
    if(b?.hasAttribute('data-ui-close'))close();
    if(b?.hasAttribute('data-ui-details'))document.body.classList.toggle('detail-drawer-open');
    if(b?.hasAttribute('data-ui-drawer-close'))document.body.classList.remove('detail-drawer-open');
    if(b?.hasAttribute('data-ui-save'))toast('仅演示入口，未创建或修改文件。');
    if(b?.hasAttribute('data-ui-menu-action'))toast('菜单内容示意，预览不会调用实际 App。');
    if(b?.dataset.uiConfirm){
      const name=b.dataset.uiConfirm;
      if(name==='progress'){clearInterval(timer);let index=0;b.disabled=true;b.textContent='模拟处理中…';timer=setInterval(()=>{const items=dialog.querySelectorAll('.readability-progress li');if(index<items.length)items[index++].classList.add('done');else{clearInterval(timer);b.disabled=false;b.textContent='演示完成 · 未发送消息';}},500);}
      else{close();toast(name==='account'?'表单预览已关闭，应用账号未修改。':name==='updates'?'更新提示样式预览结束，没有发起下载。':'样式示例已关闭。');}
    }
    if(!event.target.closest('input')&&event.target.closest('[data-asset],[data-capability]'))document.body.classList.add('detail-drawer-open');
  });
  dialog.addEventListener('close',()=>clearInterval(timer));
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!dialog.open)document.body.classList.remove('detail-drawer-open');});
})();
