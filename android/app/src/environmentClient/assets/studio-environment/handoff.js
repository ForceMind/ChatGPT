(() => {
  'use strict';
  if (window !== top) return;
  const channel = 'studioEnvironmentHandoff', documentId = crypto.randomUUID();
  let port, auth = null, sequence = 0, previous = null, menuOpened = false, uploadMenuOpened = false, closed = false;
  let pageErrors = 0, rejections = 0;
  const visible = node => !!node && !!node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
  const editor = () => Array.from(document.querySelectorAll('textarea#prompt-textarea,#prompt-textarea[contenteditable="true"],textarea[data-testid="prompt-textarea"]')).find(node => visible(node) && !node.closest('[data-private],[autocomplete="one-time-code"]') && !['password','email'].includes(node.getAttribute('type')) && node.getAttribute('autocomplete') !== 'one-time-code');
  const scope = node => node && (node.closest('form') || node.closest('[data-type="unified-composer"]') || node.parentElement?.parentElement);
  const text = node => !node ? '' : ('value' in node ? String(node.value) : String(node.innerText || ''));
  const allowed = () => {
    const url = new URL(location.href);
    return url.protocol === 'https:' && url.searchParams.get('temporary-chat') !== 'true' && !document.querySelector('[data-testid="temporary-chat-on"],[data-testid="temporary-chat-badge"],[data-is-temporary-chat="true"]') && !/^\/(auth|api|backend-api|login|logout|signup|signin)(\/|$)/.test(url.pathname);
  };
  function reply(type, request, value = {}) {
    if (!port || !auth || closed) return;
    port.postMessage(Object.assign({type, request, token: auth.token, epoch: auth.epoch, document: documentId, url: location.href, sequence: ++sequence}, value));
  }
  function point(node) {
    if (!visible(node)) throw new Error('control-hidden');
    const rect = node.getBoundingClientRect(), x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
    const hit = document.elementFromPoint(x, y);
    if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x >= innerWidth || y >= innerHeight || !(hit === node || node.contains(hit))) throw new Error('control-covered');
    return {x, y, viewportWidth: innerWidth, viewportHeight: innerHeight};
  }
  function state() {
    if (!allowed()) throw new Error('document');
    const input = editor(), form = scope(input), value = text(input);
    const messages = Array.from(document.querySelectorAll('main [data-message-author-role="user"]'));
    const last = messages.length ? String(messages[messages.length - 1].innerText || '') : '';
    if (value.length > 70000 || last.length > 70000) throw new Error('text-limit');
    const removers = Array.from((form || document).querySelectorAll('button[aria-label*="Remove attachment"],button[aria-label*="Remove file"],button[aria-label*="移除附件"],button[aria-label*="删除附件"]')).filter(visible);
    const attachments = Array.from((form || document).querySelectorAll('[data-testid="attachment"],[data-testid="file-thumbnail"],[data-attachment-name]')).filter(visible).map(node => String(node.getAttribute('data-attachment-name') || node.getAttribute('title') || node.innerText || '').slice(0,1000));
    removers.forEach(node => attachments.push(String((node.getAttribute('aria-label') || '') + ' ' + (node.parentElement?.textContent || '')).slice(0,1000)));
    const send = form?.querySelector('button[data-testid="send-button"],button[aria-label="Send prompt"],button[aria-label="Send message"],button[aria-label="发送提示"],button[aria-label="发送消息"]');
    const busy = !!Array.from((form || document).querySelectorAll('[role="progressbar"],button[aria-label*="Cancel upload"],button[aria-label*="取消上传"]')).find(visible);
    const error = !!Array.from((form || document).querySelectorAll('[role="alert"]')).find(visible);
    const generating = !!Array.from(document.querySelectorAll('[data-testid="stop-button"],[data-testid="stop-generation-button"],button[aria-label="Stop generating"],button[aria-label="停止生成"]')).find(visible);
    const result = {composer: !!input, focused: !!input && (document.activeElement === input || input.contains(document.activeElement)), text: value, messages: messages.length, last, attachments, removers: removers.length, busy, error, generating, fresh: location.pathname === '/', send: visible(send) && !send.disabled};
    if (auth?.diagnostics) {const selected = document.querySelector('input[type="file"]');result.diagnostics = {selectedFiles:selected?.files?.length || 0,pageErrors,rejections};}
    return result;
  }
  function filesReady(value) {
    return !value.busy && !value.error && value.removers === auth.names.length && auth.names.every(name => value.attachments.some(label => label.includes(name)));
  }
  function restore() {
    if (!previous) return;
    const saved = previous; previous = null;
    saved.input.style.cssText = saved.style;
    if (saved.accept === null) saved.input.removeAttribute('accept'); else saved.input.setAttribute('accept', saved.accept);
    if (saved.multiple === null) saved.input.removeAttribute('multiple'); else saved.input.setAttribute('multiple', saved.multiple);
  }
  function upload() {
    const current = state();
    if (!current.fresh || current.text || current.messages || current.generating || current.error) throw new Error('existing-content');
    const input = Array.from(document.querySelectorAll('input[type="file"]')).find(node => !node.webkitdirectory && !node.disabled && (!/^image\/[^,]+$/.test(node.accept) || auth.allImages));
    if (!input) {
      if (!menuOpened) {const plus = document.querySelector('button[data-testid="composer-plus-btn"]'); if (plus) {menuOpened = true; return {wait: true, menu: point(plus)};}}
      if (!uploadMenuOpened) {
        const item = Array.from(document.querySelectorAll('[role="menuitem"],button')).find(node => visible(node) && /upload (photos|files)|add (photos|files)|上传照片|上传文件|添加照片|添加文件/i.test(node.textContent || ''));
        if (item) {uploadMenuOpened = true; return {wait: true, menu: point(item)};}
      }
      return {wait: true};
    }
    if (!previous) previous = {input, style: input.style.cssText, accept: input.getAttribute('accept'), multiple: input.getAttribute('multiple')};
    if (previous.input !== input) throw new Error('file-input-changed');
    input.multiple = true;
    input.accept = (previous.accept ? previous.accept + ',' : '') + auth.mimeMarker;
    for (const [name, value] of [['position','fixed'],['left','8px'],['top','8px'],['width','32px'],['height','32px'],['display','block'],['visibility','visible'],['opacity','0.01'],['pointer-events','auto'],['z-index','2147483647']]) input.style.setProperty(name, value, 'important');
    return point(input);
  }
  function command(message) {
    if (!auth || message.token !== auth.token || message.epoch !== auth.epoch || message.document !== documentId || typeof message.request !== 'string') return;
    const acknowledgement = message.action === 'ack-state';
    const conversation = new URL(location.href).origin === new URL(auth.url).origin && /^\/c\//.test(location.pathname);
    if ((!acknowledgement && location.href !== auth.url) || (acknowledgement && location.href !== auth.url && !conversation) || !allowed()) {reply('error', message.request, {reason:'document'}); return;}
    try {
      let value;
      switch (message.action) {
        case 'state': case 'ack-state': value = state(); break;
        case 'upload': value = upload(); break;
        case 'restore': restore(); value = {ok:true}; break;
        case 'focus': {
          const current = state();
          if (!current.fresh || !current.composer || current.text || current.messages || current.generating || !filesReady(current)) throw new Error('existing-content');
          editor().focus(); value = {ok:true}; break;
        }
        case 'send-point': {
          const current = state();
          if (!current.fresh || !current.send || current.messages || current.generating || !filesReady(current)) throw new Error('changed');
          const form = scope(editor());
          const button=form.querySelector('button[data-testid="send-button"],button[aria-label="Send prompt"],button[aria-label="Send message"],button[aria-label="发送提示"],button[aria-label="发送消息"]');
          button.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});
          try {value=Object.assign({state:current},point(button));}
          catch (error) {if(error.message!=='control-covered'&&error.message!=='control-hidden')throw error;value={state:current,wait:true};}
          break;
        }
        case 'cleanup': restore(); value = {ok:true}; break;
        default: throw new Error('command');
      }
      reply('result', message.request, {value});
    } catch (error) {reply('error', message.request, {reason: /^[a-z-]{1,40}$/.test(error.message) ? error.message : 'page'});}
  }
  try {
    port = browser.runtime.connectNative(channel);
    port.onMessage.addListener(message => {
      if (!message || typeof message !== 'object') return;
      if (message.type === 'auth' && !auth && message.document === documentId && message.url === location.href && typeof message.token === 'string' && Number.isSafeInteger(message.epoch) && Array.isArray(message.names) && message.names.length <= 20 && message.names.every(name => typeof name === 'string') && typeof message.mimeMarker === 'string') {
        auth = message;
        if (auth.diagnostics) {addEventListener('error',()=>{pageErrors++;},true);addEventListener('unhandledrejection',()=>{rejections++;},true);}
        reply('ready', message.request, {value:state()});
      } else if (message.type === 'command') command(message);
    });
    port.onDisconnect.addListener(() => {restore();closed=true;auth=null;port=null;});
    port.postMessage({type:'hello',document:documentId,url:location.href});
    addEventListener('pagehide', () => {restore();closed=true;auth=null;}, {once:true});
  } catch (_) {restore();closed=true;}
})();
