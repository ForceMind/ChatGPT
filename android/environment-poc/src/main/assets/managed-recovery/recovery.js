/* App-owned fixture adapter. The acceptance server independently observes the
 * real DOM/model. No expected draft values or test markers are embedded here. */
(() => {
  const u = new URL(location.href);
  if (window !== window.top || u.origin !== "http://127.0.0.1:8765" || u.pathname !== "/recovery") return;
  const standard = document.getElementById("standard"), rich = document.getElementById("rich");
  if (!standard || !rich || !rich.isContentEditable) return;
  const documentId = crypto.randomUUID(), initialURL = location.href;
  const port = browser.runtime.connectNative("studioRecovery");
  let token = null, epoch = 0, sequence = 0, disconnected = false, scheduled = null, announced = false, metrics = null, resumeScroll = null, expectedFocus = null;
  let resourceTuned=false,lastCapture=null;
  function snapshot() {
    if (window.innerHeight>0 && window.innerWidth>0) metrics={scroll:window.scrollY,height:document.documentElement.scrollHeight,viewport:window.innerHeight};
    if (!metrics) return null;
    return {url:location.href, standard:standard.value, rich:rich.innerText || "",
      transcript:Array.from(document.querySelector("article")?.children || []).map(n => n.textContent || "").join("\n\n").slice(0,50000),
      ...metrics};
  }
  function send(type, extra = {}) {
    if (disconnected || location.href !== initialURL) return;
    port.postMessage({type, url:location.href, document:documentId, token, epoch, sequence:++sequence, ...extra});
  }
  function capture(force=false) {
    const value=snapshot();if (!token || !value) return;
    const text=resourceTuned?JSON.stringify(value):null;
    if(resourceTuned&&announced&&force!==true&&text===lastCapture)return;
    send(announced?"capture":"ready", {snapshot:value});lastCapture=text;announced=true;
  }
  function later() { clearTimeout(scheduled); scheduled = setTimeout(capture, 160); }
  function receive(message) {
    if (disconnected || !message || location.href !== initialURL) return;
    if (message.type === "hello-request") { if(token)capture(true);else send("hello");return; }
    if (message.type === "auth") {
      token = message.token; epoch = message.epoch;
      resourceTuned=message.resourceTuned===true;lastCapture=null;capture(true);
      return;
    }
    if (!token || message.token !== token || message.epoch !== epoch) return;
    if(typeof message.resourceTuned==='boolean')resourceTuned=message.resourceTuned;
    if(message.type==='capture-policy'){lastCapture=null;return;}
    if (message.type === "focus") {
      const element = message.editor === "standard" ? standard : message.editor === "rich" ? rich : null;
      if (!element) return;
      expectedFocus = element;
      // Require a fresh native IME focus notification for each restore target,
      // including a field that retained DOM focus across an error-page retry.
      if (document.activeElement === standard || document.activeElement === rich) document.activeElement.blur();
      element.scrollIntoView({block:"center",behavior:"instant"});
      requestAnimationFrame(() => requestAnimationFrame(() => {
        const value=snapshot(),r=element.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2,hit=document.elementFromPoint(x,y);
        if(value)send("focus-target",{editor:message.editor,point:{x,y,ratio:devicePixelRatio},unobscured:hit===element||element.contains(hit),snapshot:value});
      }));
    } else if (message.type === "scroll") {
      if (document.activeElement === standard || document.activeElement === rich) document.activeElement.blur();
      // Drop the selection created by our restore input before returning to the
      // reading position. Gecko may otherwise scroll the old caret into view.
      window.getSelection()?.removeAllRanges();
      const started = performance.now();let stable = null;
      const settle = () => {
        if (disconnected || location.href !== initialURL) return;
        if (window.innerHeight<=0 || window.innerWidth<=0) {resumeScroll=() => receive(message);return;}
        const top = Math.max(0,Math.min(Number(message.top),document.documentElement.scrollHeight-window.innerHeight));
        if (Math.abs(window.scrollY-top)>1) { stable=null;window.scrollTo({top,behavior:"instant"}); }
        else if (stable===null) stable=performance.now();
        if ((stable!==null && performance.now()-stable>=400 && performance.now()-started>=1000) || performance.now()-started>=3000) {
          send("scrolled", {snapshot:snapshot()});return;
        }
        setTimeout(settle,50);
      };
      requestAnimationFrame(settle);
    } else if (message.type === "checkpoint") {
      const value=snapshot();if(value)send("checkpoint", {request:message.request, snapshot:value});
    }
  }
  port.onMessage.addListener(receive);
  port.onDisconnect.addListener(() => { disconnected = true; clearTimeout(scheduled); });
  addEventListener("input", () => queueMicrotask(capture), true);
  addEventListener("click", event => {
    const element=event.target===standard?standard:rich.contains(event.target)?rich:null;
    if(!element || element!==expectedFocus || !event.isTrusted || document.activeElement!==element) return;
    expectedFocus=null;
    requestAnimationFrame(() => {const value=snapshot();if(value)send("focused",{editor:element===standard?"standard":"rich",active:true,snapshot:value});});
  }, true);
  addEventListener("scroll", later, {passive:true});
  addEventListener("resize", () => {later();if(resumeScroll && window.innerHeight>0 && window.innerWidth>0){const resume=resumeScroll;resumeScroll=null;resume();}});
  const transcript = document.querySelector("article");
  if (transcript) new MutationObserver(later).observe(transcript,{childList:true,subtree:true,characterData:true});
  addEventListener("pagehide", () => capture(true), {once:true});
  send("hello");
})();
