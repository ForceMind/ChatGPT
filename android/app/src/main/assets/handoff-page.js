(function(config){
  'use strict';
  if(window!==top||location.origin!==config.origin)return JSON.stringify({error:'origin'});
  var previous=null,menuOpened=false,uploadMenuOpened=false;
  var visible=function(e){return !!e&&!!e.getClientRects().length;};
  var composer=function(){return Array.from(document.querySelectorAll('#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]')).find(visible);};
  var scope=function(e){return e&&(e.closest('form')||e.closest('[data-type="unified-composer"]')||(e.parentElement&&e.parentElement.parentElement));};
  var normal=function(value){return String(value||'').replace(/\r\n/g,'\n').replace(/\u00a0/g,' ').trim();};
  var inputs=function(){return Array.from(document.querySelectorAll('input[type="file"]')).filter(function(i){return !i.webkitdirectory&&!i.disabled&&(!/^image\/[^,]+$/.test(i.accept)||config.allImages);});};
  var observe=function(ack){
    var e=composer(),s=scope(e),send=s&&s.querySelector('button[data-testid="send-button"],button[aria-label="Send prompt"],button[aria-label="发送提示"],button[aria-label="发送消息"],button[aria-label="Send message"]');
    var removers=Array.from((s||document).querySelectorAll('button[aria-label*="Remove"],button[aria-label*="remove"],button[aria-label*="移除"],button[aria-label*="删除附件"],[data-testid="remove-file"]')).filter(visible);
    var names=Array.from((s||document).querySelectorAll('[data-testid="attachment"],[data-testid="file-thumbnail"],[data-attachment-name],[title]')).filter(visible).map(function(n){return n.getAttribute('data-attachment-name')||n.getAttribute('title')||n.innerText||'';});
    removers.forEach(function(n){names.push((n.getAttribute('aria-label')||'')+' '+(n.parentElement?n.parentElement.textContent:''));});
    var messages=Array.from(document.querySelectorAll('[data-message-author-role="user"]'));
    return {composer:!!e,input:inputs().length>0,text:e?(e.tagName==='TEXTAREA'?e.value:e.innerText||'').slice(0,70000):'',send:visible(send)&&!send.disabled,attachments:names.map(function(n){return String(n).slice(0,1000);}).slice(0,100),removers:removers.length,busy:!!(s&&s.querySelector('[role="progressbar"],button[aria-label*="Cancel upload"],button[aria-label*="取消上传"]')),error:!!(s&&Array.from(s.querySelectorAll('[role="alert"]')).find(visible)),messages:messages.length,last:ack&&messages.length?(messages[messages.length-1].innerText||'').slice(0,70000):'',generating:!!document.querySelector('[data-testid="stop-button"]'),fresh:location.pathname==='/',login:!e&&!!Array.from(document.querySelectorAll('a,button')).find(function(n){return visible(n)&&/^(log in|登录|登入)$/i.test((n.innerText||'').trim());})};
  };
  var filesReady=function(state){return !state.busy&&!state.error&&state.removers===config.names.length&&config.names.every(function(name){return state.attachments.some(function(n){return n.indexOf(name)>=0;});});};
  var restore=function(){if(previous){var p=previous;previous=null;p.input.style.cssText=p.style;p.input.accept=p.accept;p.input.multiple=p.multiple;}return {ok:true};};
  var api={token:config.token,state:observe,restore:restore,
    upload:function(){
      var i=inputs()[0];
      if(!i){
        if(!menuOpened){var plus=document.querySelector('button[data-testid="composer-plus-btn"]');if(plus){menuOpened=true;plus.click();return {wait:true};}}
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
    focus:function(){var state=observe(false),e=composer();if(!state.fresh||!e||normal(state.text)||state.messages||state.generating||!filesReady(state))return {error:'existing-draft'};e.focus();return {ok:true};},
    send:function(value){var state=observe(false),e=composer(),s=scope(e);if(!state.fresh||normal(state.text)!==normal(value.prompt)||!state.send||!filesReady(state)||state.messages||state.generating)return {error:'changed'};var b=s.querySelector('button[data-testid="send-button"],button[aria-label="Send prompt"],button[aria-label="发送提示"],button[aria-label="发送消息"],button[aria-label="Send message"]');if(!b||b.disabled)return {error:'send'};b.click();return {ok:true};},
    cleanup:function(){restore();delete window[config.key];return {ok:true};}
  };
  Object.defineProperty(window,config.key,{value:api,configurable:true});
  return JSON.stringify({ok:true});
})(__CONFIG__)
