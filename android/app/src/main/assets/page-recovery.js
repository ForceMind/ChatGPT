(function(config){
  'use strict';
  if(window!==top||!/^https:\/\/(chatgpt\.com|chat\.openai\.com)$/.test(location.origin))return JSON.stringify({status:'skip'});
  var previous=window[config.key];if(previous)previous.cleanup();
  var dirty=true,cancelled=false;
  var visible=function(e){return !!e&&!!e.getClientRects().length;};
  var composer=function(){return Array.from(document.querySelectorAll('#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]')).find(visible);};
  var sensitive='input[type="password" i],input[type="email" i],input[autocomplete="username" i],input[autocomplete="current-password" i],input[autocomplete="new-password" i],input[autocomplete="one-time-code" i]';
  var unsafe=function(){return new URLSearchParams(location.search).getAll('temporary-chat').some(function(v){return v.toLowerCase()==='true';})||Array.from(document.querySelectorAll(sensitive)).some(visible);};
  var text=function(e){return e?(e.tagName==='TEXTAREA'?e.value:e.innerText||''):'';};
  var conversation=function(){
    var path;try{path=decodeURIComponent(location.pathname);}catch(e){return null;}var match=/^\/(?:c\/([^/]+)|g\/[^/]+\/c\/([^/]+))\/?$/.exec(path);
    return !!match&&String(match[1]||match[2]).toLowerCase().indexOf('local-chatgpt:')!==0;
  };
  var snapshot=function(){
    if(!conversation())return null;
    var nodes=Array.from(document.querySelectorAll('[data-message-author-role]'));
    if(!nodes.length)nodes=Array.from(document.querySelectorAll('[data-message-id]'));
    var messages=[],remaining=60000,truncated=false;
    for(var i=0;i<nodes.length;i++){
      var value=(nodes[i].innerText||'').replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g,'');if(!value.trim())continue;
      if(messages.length>=300||remaining<=0){truncated=true;break;}
      var part=value.slice(0,remaining);if(part.length<value.length&&/[\uD800-\uDBFF]$/.test(part))part=part.slice(0,-1);
      if(part.length<value.length)truncated=true;if(!part)break;remaining-=part.length;
      var role=nodes[i].getAttribute('data-message-author-role'),ancestor=nodes[i].closest('[data-message-id]'),id=nodes[i].getAttribute('data-message-id')||nodes[i].id||(ancestor&&ancestor.getAttribute('data-message-id'));
      var message={role:role==='user'?'user':role==='assistant'?'assistant':'message',text:part};if(id&&id.length<=160)message.id=id;messages.push(message);
    }
    if(!messages.length)return null;
    return {title:(document.title||'ChatGPT').replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g,'').slice(0,240),messages:messages,truncated:truncated,capturedAt:Date.now()};
  };
  var scroller=function(e){var candidates=new Set();if(document.scrollingElement)candidates.add(document.scrollingElement);var parent=document.querySelector('[data-message-id],[data-message-author-role]')||e;while(parent){candidates.add(parent);parent=parent.parentElement;}return Array.from(candidates).filter(function(n){return visible(n)&&n.scrollHeight>n.clientHeight+40&&n.clientHeight>100;}).sort(function(a,b){return b.scrollHeight-a.scrollHeight;})[0]||document.documentElement;};
  var changed=function(){dirty=true;};
  var observer=new MutationObserver(function(changes){
    for(var i=0;i<changes.length;i++){
      var target=changes[i].target.nodeType===1?changes[i].target:changes[i].target.parentElement;
      if(target&&target.closest('[data-message-id],[data-message-author-role]')){dirty=true;return;}
      for(var j=0;j<changes[i].addedNodes.length;j++){var n=changes[i].addedNodes[j];if(n.nodeType===1&&(n.matches('[data-message-id],[data-message-author-role]')||n.querySelector('[data-message-id],[data-message-author-role]'))){dirty=true;return;}}
    }
  });
  observer.observe(document.documentElement,{subtree:true,childList:true,characterData:true});
  var interaction=function(event){if(event.isTrusted)cancelled=true;};
  var events=['pointerdown','wheel','touchstart','keydown'];
  window.addEventListener('input',changed,true);window.addEventListener('scroll',changed,true);events.forEach(function(name){window.addEventListener(name,interaction,{capture:true,passive:true});});
  var api={token:config.token,
    capture:function(force){
      if(unsafe())return {status:'skip'};if(!force&&!dirty)return {status:'same'};
      var e=composer(),messages=document.querySelectorAll('[data-message-id],[data-message-author-role]');if(!e&&!messages.length)return {status:'skip'};
      var value=text(e);if(value.length>100000)return {status:'oversize'};
      var container=scroller(e),top=container===document.scrollingElement?0:container.getBoundingClientRect().top,first=Array.from(messages).find(function(n){return n.getBoundingClientRect().bottom>top+10;});
      var record={status:'captured',url:location.href,draft:value,scroll:Math.max(0,container.scrollTop),ratio:Math.max(0,Math.min(1,container.scrollTop/Math.max(1,container.scrollHeight-container.clientHeight)))};
      var page=snapshot();if(page)record.page=page;
      var id=first&&(first.getAttribute('data-message-id')||first.id);if(id&&id.length<=160)record.anchor={kind:first.hasAttribute('data-message-id')?'message':'id',value:id,offset:first.getBoundingClientRect().top-top};dirty=false;return record;
    },
    pictureSafe:function(){
      if(unsafe()||document.querySelector(sensitive)||Array.from(document.querySelectorAll('iframe')).some(visible))return {status:'skip'};
      return {status:conversation()&&document.querySelector('[data-message-id],[data-message-author-role]')?'safe':'skip'};
    },
    restore:function(record,force){
      if(cancelled||unsafe())return {status:'cancelled'};
      var e=composer(),visibleMessages=document.querySelectorAll('[data-message-id],[data-message-author-role]');
      if(!e&&!visibleMessages.length)return {status:force?'unavailable':'waiting'};
      var container=scroller(e),max=Math.max(0,container.scrollHeight-container.clientHeight),anchor=record.anchor;
      var element=anchor?(anchor.kind==='message'?document.querySelector('[data-message-id="'+CSS.escape(anchor.value)+'"]'):document.getElementById(anchor.value)):null;
      if(!force&&record.scroll>0&&!element&&max<record.scroll-5)return {status:'waiting'};
      if(element&&anchor){var top=container===document.scrollingElement?0:container.getBoundingClientRect().top;container.scrollTop+=element.getBoundingClientRect().top-top-anchor.offset;}
      else container.scrollTop=record.scroll<=max?record.scroll:record.ratio*max;
      if(!record.draft)return {status:'restored'};if(!e)return {status:'waiting'};
      var current=text(e);if(current.trim())return {status:current===record.draft?'restored':'conflict'};
      var users=document.querySelectorAll('[data-message-author-role=user]'),last=users.length?users[users.length-1].innerText:'';
      var normal=function(s){return String(s||'').replace(/\s+/g,' ').trim();};if(normal(last)===normal(record.draft))return {status:'already-sent'};
      if(document.querySelector('[data-testid=stop-button]'))return {status:'conflict'};
      if(e.tagName==='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(e,record.draft);e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:record.draft}));}
      else{var reading=container.scrollTop;e.focus({preventScroll:true});if(document.activeElement!==e&&!e.contains(document.activeElement))return {status:'conflict'};if(!document.execCommand('insertText',false,record.draft))return {status:'conflict'};container.scrollTop=reading;}
      return {status:text(e)===record.draft?'restored':'conflict'};
    },
    finish:function(){events.forEach(function(name){window.removeEventListener(name,interaction,true);});return {status:'finished'};},
    cleanup:function(){api.finish();observer.disconnect();window.removeEventListener('input',changed,true);window.removeEventListener('scroll',changed,true);delete window[config.key];}
  };
  Object.defineProperty(window,config.key,{value:api,configurable:true});return JSON.stringify({status:'installed'});
})(__CONFIG__)
