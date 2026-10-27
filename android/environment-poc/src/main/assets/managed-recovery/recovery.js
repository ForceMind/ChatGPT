/* App-owned fixture adapter. The acceptance server independently observes the
 * real DOM/model. No expected draft values or test markers are embedded here. */
(() => {
  const u = new URL(location.href);
  if (window !== window.top || u.origin !== "http://127.0.0.1:8765" || u.pathname !== "/recovery") return;
  const standard = document.getElementById("standard"), rich = document.getElementById("rich");
  if (!standard || !rich || !rich.isContentEditable) return;
  const documentId = crypto.randomUUID(), initialURL = location.href;
  const port = browser.runtime.connectNative("studioRecovery");
  let token = null, epoch = 0, sequence = 0, disconnected = false, scheduled = null;
  function snapshot() {
    return {url:location.href, standard:standard.value, rich:rich.innerText || "",
      transcript:Array.from(document.querySelector("article")?.children || []).map(n => n.textContent || "").join("\n\n").slice(0,50000),
      scroll:window.scrollY, height:document.documentElement.scrollHeight, viewport:window.innerHeight};
  }
  function send(type, extra = {}) {
    if (disconnected || location.href !== initialURL) return;
    port.postMessage({type, url:location.href, document:documentId, token, epoch, sequence:++sequence, ...extra});
  }
  function capture() { if (token) send("capture", {snapshot:snapshot()}); }
  function later() { clearTimeout(scheduled); scheduled = setTimeout(capture, 160); }
  port.onMessage.addListener(message => {
    if (!message || location.href !== initialURL) return;
    if (message.type === "auth") {
      token = message.token; epoch = message.epoch;
      send("ready", {snapshot:snapshot()});
      return;
    }
    if (!token || message.token !== token || message.epoch !== epoch) return;
    if (message.type === "focus") {
      const element = message.editor === "standard" ? standard : message.editor === "rich" ? rich : null;
      if (!element) return;
      element.focus({preventScroll:true});
      requestAnimationFrame(() => send("focused", {editor:message.editor, active:document.activeElement === element, snapshot:snapshot()}));
    } else if (message.type === "scroll") {
      if (document.activeElement === standard || document.activeElement === rich) document.activeElement.blur();
      // Drop the selection created by our restore input before returning to the
      // reading position. Gecko may otherwise scroll the old caret into view.
      window.getSelection()?.removeAllRanges();
      const started = performance.now();let stable = null;
      const settle = () => {
        if (disconnected || location.href !== initialURL) return;
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
      send("checkpoint", {request:message.request, snapshot:snapshot()});
    }
  });
  port.onDisconnect.addListener(() => { disconnected = true; clearTimeout(scheduled); });
  addEventListener("input", () => queueMicrotask(capture), true);
  addEventListener("scroll", later, {passive:true});
  const transcript = document.querySelector("article");
  if (transcript) new MutationObserver(later).observe(transcript,{childList:true,subtree:true,characterData:true});
  addEventListener("pagehide", capture, {once:true});
  send("hello");
})();
