(function(config){
  'use strict';
  if(window!==top||location.origin!==config.origin)return JSON.stringify({error:'origin'});
  var previous=null,menuOpened=false,uploadMenuOpened=false;
  var visible=function(e){return !!e&&!!e.getClientRects().length;};
  var composer=function(){return Array.from(document.querySelectorAll('#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"],.ProseMirror[contenteditable="true"][role="textbox"][aria-label="询问 ChatGPT"],.ProseMirror[contenteditable="true"][role="textbox"][aria-label="Ask ChatGPT"]')).find(e => visible(e) && (e.tagName === 'TEXTAREA' ? !e.disabled && !e.readOnly : e.isContentEditable));};
  const addSelector = "button[data-testid=\"composer-plus-btn\"],button[aria-label=\"添加文件等内容\"],button[aria-label=\"添加文件等\"],button[aria-label=\"Add files and more\"]";
  const sendSelector = "button[data-testid=\"send-button\"],button[aria-label=\"Send prompt\"],button[aria-label=\"Send message\"],button[aria-label=\"发送提示\"],button[aria-label=\"发送消息\"],button[aria-label=\"发送\"]";
  function scope(node) {
    if (!node) return null;
    const form = node.closest('[data-type="unified-composer"],form');
    if (form) return form;
    for (let group = node.parentElement; group && !group.matches('main,body,html'); group = group.parentElement)
      if (group.querySelector(addSelector)) return group;
    return node.parentElement && node.parentElement.parentElement;
  }
  function userMessages() {
    const legacy = Array.from(document.querySelectorAll('[data-message-author-role="user"]'));
    if (legacy.length || !location.pathname.startsWith('/c/')) return legacy;
    return Array.from(document.querySelectorAll('main .bg-user-message .whitespace-pre-wrap')).filter(visible);
  }
  function readEditor(node) {
    if (!node) return '';
    if (node.tagName === 'TEXTAREA') return node.value;
    function readChildren(parent) {
      let value = '';
      const children = Array.from(parent.childNodes);
      children.forEach((child, index) => {
        if (child.nodeType === Node.TEXT_NODE) { value += child.nodeValue || ''; return; }
        if (child.nodeType !== Node.ELEMENT_NODE) return;
        if (child.tagName === 'BR') { if (!child.classList.contains('ProseMirror-trailingBreak')) value += '\n'; return; }
        const block = /^(P|DIV|LI|PRE|BLOCKQUOTE)$/.test(child.tagName);
        if (block && value && !value.endsWith('\n')) value += '\n';
        value += readChildren(child);
        if (block && index < children.length - 1) value += '\n';
      });
      return value;
    }
    return readChildren(node);
  }
  function filesMatch(removers, labels, expected) {
    if (removers.length !== expected.length) return false;
    const reported = removers.map(button => {
      const match = (button.getAttribute('aria-label') || '').match(/^(?:remove|移除|删除附件)\s+(.+)$/i);
      const label = match && match[1].trim();
      return label && !/^(?:attachment|file|附件)$/i.test(label) ? label : null;
    }).filter(Boolean);
    if (reported.length !== expected.length) return expected.every(name => labels.some(label => label.includes(name)));
    const withoutKind = label => label.replace(/^(?:attachment|file|附件)\s+/i, '');
    const renamed = expected.filter(name => {
      let exact = reported.indexOf(name);
      if (exact < 0) exact = reported.findIndex(label => withoutKind(label) === name);
      if (exact < 0) return true;
      reported.splice(exact, 1);
      return false;
    });
    return renamed.every(name => {
      const dot = name.lastIndexOf('.');
      const stem = dot > 0 ? name.slice(0, dot) : name;
      const extension = dot > 0 ? name.slice(dot) : '';
      const match = reported.findIndex(label => [label, withoutKind(label)].some(candidate =>
        candidate.startsWith(stem) && candidate.endsWith(extension) &&
        /^ ?\([1-9]\d*\)$/.test(candidate.slice(stem.length, candidate.length - extension.length))));
      if (match < 0) return false;
      reported.splice(match, 1);
      return true;
    });
  }
  const enabledSend = form => Array.from(form ? form.querySelectorAll(sendSelector) : []).find(button => visible(button) && !button.disabled && button.getAttribute('aria-disabled') !== 'true');
  var normal=function(value){return String(value||'').replace(/\r\n/g,'\n').replace(/\u00a0/g,' ').trim();};
  var inputs=function(){return Array.from(document.querySelectorAll('input[type="file"]')).filter(function(i){return !i.webkitdirectory&&!i.disabled&&(!/^image\/[^,]+$/.test(i.accept)||config.allImages);});};
  var observe=function(ack){
    var e=composer(),s=scope(e),send=enabledSend(s);
    var removers=Array.from((s||document).querySelectorAll('button[aria-label*="Remove"],button[aria-label*="remove"],button[aria-label*="移除"],button[aria-label*="删除附件"],[data-testid="remove-file"]')).filter(visible);
    var names=Array.from((s||document).querySelectorAll('[data-testid="attachment"],[data-testid="file-thumbnail"],[data-attachment-name],[title]')).filter(visible).map(function(n){return n.getAttribute('data-attachment-name')||n.getAttribute('title')||n.innerText||'';});
    removers.forEach(function(n){names.push((n.getAttribute('aria-label')||'')+' '+(n.parentElement?n.parentElement.textContent:''));});
    var messages=userMessages();
    return {composer:!!e,focused:!!e&&(document.activeElement===e||e.contains(document.activeElement)),input:inputs().length>0,text:readEditor(e).slice(0,70000),send:visible(send)&&!send.disabled,attachments:names.map(function(n){return String(n).slice(0,1000);}).slice(0,100),removers:removers.length,filesMatch:filesMatch(removers,names,config.names),busy:!!(s&&s.querySelector('[role="progressbar"],button[aria-label*="Cancel upload"],button[aria-label*="取消上传"]')),error:!!(s&&Array.from(s.querySelectorAll('[role="alert"]')).find(visible)),messages:messages.length,last:ack&&messages.length?(messages[messages.length-1].innerText||messages[messages.length-1].textContent||'').slice(0,70000):'',generating:!!document.querySelector('[data-testid="stop-button"]'),fresh:location.pathname==='/',login:!e&&!!Array.from(document.querySelectorAll('a,button')).find(function(n){return visible(n)&&/^(log in|登录|登入)$/i.test((n.innerText||'').trim());})};
  };
  var filesReady=function(state){return !state.busy&&!state.error&&state.filesMatch;};
  var restore=function(){if(previous){var p=previous;previous=null;p.input.style.cssText=p.style;p.input.accept=p.accept;p.input.multiple=p.multiple;}return {ok:true};};
  var api={token:config.token,state:observe,restore:restore,
    upload:function(){
      var i=inputs()[0];
      if(!i){
        if(!menuOpened){var form=scope(composer()),plus=form && form.querySelector(addSelector);if(plus){menuOpened=true;plus.click();return {wait:true};}}
        if(!uploadMenuOpened){var item=Array.from(document.querySelectorAll('[role="menuitem"],button')).find(function(n){return visible(n)&&/upload (photos|files)|add (photos|files)|上传照片|上传文件|添加照片|添加文件/i.test(n.textContent||'');});if(item){uploadMenuOpened=true;item.click();}}
        return {wait:true};
      }
      if(!previous)previous={input:i,style:i.style.cssText,accept:i.accept,multiple:i.multiple};
      i.multiple=true;i.accept=(previous.accept?previous.accept+',':'')+config.mimeMarker;
      [['position','fixed'],['left','8px'],['top','8px'],['width','32px'],['height','32px'],['display','block'],['visibility','visible'],['opacity','0.01'],['pointer-events','auto'],['z-index','2147483647']].forEach(function(p){i.style.setProperty(p[0],p[1],'important');});
      var r=i.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
      if(!r.width||!r.height||document.elementFromPoint(x,y)!==i)return {error:'upload-hidden'};
      return {x:x,y:y};
    },
    focus:function(){var state=observe(false),e=composer();var draft=normal(state.text);if(!state.fresh||!e||(draft&&draft!==normal(config.prompt))||state.messages||state.generating||!filesReady(state))return {error:'existing-draft'};if(!draft)e.focus();return {ok:true,reused:!!draft};},
    send:function(value){var state=observe(false),e=composer(),s=scope(e);if(!state.fresh||normal(state.text)!==normal(value.prompt)||!state.send||!filesReady(state)||state.messages||state.generating)return {error:'changed'};var b=enabledSend(s);if(!b||b.disabled)return {error:'send'};b.click();return {ok:true};},
    cleanup:function(){restore();delete window[config.key];return {ok:true};}
  };
  Object.defineProperty(window,config.key,{value:api,configurable:true});
  return JSON.stringify({ok:true});
})(__CONFIG__)
