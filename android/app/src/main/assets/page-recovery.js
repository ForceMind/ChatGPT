(function(config){
  'use strict';
  if(window!==top||!/^https:\/\/(chatgpt\.com|chat\.openai\.com)$/.test(location.origin))return JSON.stringify({status:'skip'});
  var previous=window[config.key];if(previous)previous.cleanup();
  var dirty=true,cancelled=false;
  var visible=function(e){return !!e&&!!e.getClientRects().length;};
  var composer=function(){return Array.from(document.querySelectorAll('#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]')).find(visible);};
  var unsafe=function(){return new URLSearchParams(location.search).getAll('temporary-chat').some(function(v){return v.toLowerCase()==='true';})||Array.from(document.querySelectorAll('input[type=password],input[autocomplete=one-time-code]')).some(visible);};
  var text=function(e){return e?(e.tagName==='TEXTAREA'?e.value:e.innerText||''):'';};
  var scroller=function(e){var candidates=new Set();if(document.scrollingElement)candidates.add(document.scrollingElement);var parent=document.querySelector('[data-message-id],[data-message-author-role]')||e;while(parent){candidates.add(parent);parent=parent.parentElement;}return Array.from(candidates).filter(function(n){return visible(n)&&n.scrollHeight>n.clientHeight+40&&n.clientHeight>100;}).sort(function(a,b){return b.scrollHeight-a.scrollHeight;})[0]||document.documentElement;};
  var changed=function(){dirty=true;};
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
      var id=first&&(first.getAttribute('data-message-id')||first.id);if(id&&id.length<=160)record.anchor={kind:first.hasAttribute('data-message-id')?'message':'id',value:id,offset:first.getBoundingClientRect().top-top};dirty=false;return record;
    },
    restore:function(record,force){
      if(cancelled||unsafe())return {status:'cancelled'};
      var e=composer(),container=scroller(e),max=Math.max(0,container.scrollHeight-container.clientHeight),anchor=record.anchor;
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
    cleanup:function(){api.finish();window.removeEventListener('input',changed,true);window.removeEventListener('scroll',changed,true);delete window[config.key];}
  };
  Object.defineProperty(window,config.key,{value:api,configurable:true});return JSON.stringify({status:'installed'});
})(__CONFIG__)
