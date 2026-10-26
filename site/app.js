"use strict";
const platforms = {
  windows: {
    image: "windows-installed.png",
    caption: "Windows · 历史 alpha.11 实际窗口 / 未登录 ChatGPT",
    alt: "Windows 已安装客户端实际截图：未登录 ChatGPT 页面、账号名称、标签页与原生窗口按钮，没有地址栏。",
    width: 1040,
    height: 728,
  },
  macos: {
    image: "macos-window.png",
    caption: "macOS · 历史公开版普通窗口 / 未登录 ChatGPT",
    alt: "macOS 客户端普通窗口实际截图：原生窗口按钮、未登录 ChatGPT、顶部标签与账号菜单，没有地址栏或远程控制标记。",
    width: 2720,
    height: 1800,
  },
  android: {
    image: "android-tabs.png",
    caption: "Android alpha.19 · 原生标签页 / 模拟器 · 本地测试账号",
    alt: "Android alpha.19 客户端原生标签页截图：账号筛选、搜索、完整边框、最近关闭和新建标签页。背后网页为本地测试素材，非真实 ChatGPT 首页。",
    width: 1080,
    height: 2400,
    kind: "ANDROID · ACTUAL APP / LOCAL FIXTURES",
  },
};
const androidViews = {
  tabs: platforms.android,
  tablet: {...platforms.android,image:"android-tablet-tabs.png",width:1600,height:2560,caption:"Android alpha.19 · 平板原生标签页 / 模拟器 · 本地测试账号",alt:"Android alpha.19 平板原生右侧标签列表，含账号筛选、搜索和管理入口；背后网页为测试素材，并非真实 ChatGPT 首页。"},
  assets: {...platforms.android,image:"android-assets.png",caption:"Android alpha.19 · 资产库 / 模拟器 · 本地示例文件",alt:"Android alpha.19 资产库原生截图：独立账号下的本地 HTML 示例文件，带搜索、导入和交给 GPT 入口；采集于 Android 15 模拟器。"},
  skills: {...platforms.android,image:"android-skills.png",caption:"Android alpha.19 · 120 个 Skill / 模拟器 · 本地示例账号",alt:"Android 客户端能力库实际截图：120 个项目 Skill、原生搜索与任务说明；采集于 Android 15 模拟器，尚无预置智能体。"},
};
const tabs = [...document.querySelectorAll("[data-platform]")];
const image = document.querySelector("#product-image");
function selectPlatform(name, view = "tabs") {
  const entry = name === "android" ? androidViews[view] : platforms[name];
  if (!entry) return;
  tabs.forEach((tab) => {
    const selected = tab.dataset.platform === name;
    tab.setAttribute("aria-selected", String(selected));
    tab.tabIndex = selected ? 0 : -1;
  });
  document
    .querySelector("#product-panel")
    .setAttribute("aria-labelledby", `tab-${name}`);
  image.hidden = !entry.image;
  document.querySelector("#product-pending").hidden = !!entry.image;
  document.querySelector("#product-capture-kind").textContent = entry.kind || "ACTUAL APP CAPTURE";
  document.querySelector("#android-capture-views").hidden = name !== "android";
  document.querySelectorAll("[data-android-view]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.androidView === view)));
  if (entry.image) {
    image.src = `/assets/${entry.image}`;
    image.alt = entry.alt;
    image.width = entry.width;
    image.height = entry.height;
  }
  image.classList.toggle("android", name === "android");
  document.querySelector("#product-caption").textContent = entry.caption;
}
document.querySelectorAll("[data-android-view]").forEach(button => button.addEventListener("click", () => selectPlatform("android", button.dataset.androidView)));
tabs.forEach((tab, index) => {
  tab.addEventListener("click", () => selectPlatform(tab.dataset.platform));
  tab.addEventListener("keydown", (event) => {
    let next;
    if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
    if (event.key === "ArrowLeft")
      next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = tabs.length - 1;
    if (next === undefined) return;
    event.preventDefault();
    selectPlatform(tabs[next].dataset.platform);
    tabs[next].focus();
  });
});
const ua = navigator.userAgent;
const os = /Android/i.test(ua)
  ? "android"
  : /iPhone|iPad|iPod/i.test(ua) ||
      (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)
    ? null
    : /Mac/i.test(navigator.platform)
      ? "macos"
      : /Win/i.test(navigator.platform)
        ? "windows"
        : null;
if (os) {
  const card = document.querySelector(`[data-os="${os}"]`);
  card.classList.add("recommended");
  // Device detection suggests a platform; the download section keeps architecture and signing requirements visible.
  document.querySelector("#primary-download span").textContent =
    `获取 ${os === "macos" ? "macOS" : os === "windows" ? "Windows" : "Android"} 客户端`;
  document.querySelectorAll('a[href="#downloads"]').forEach(link => {
    link.setAttribute("href", `#download-${os}`);
  });
  selectPlatform(os);
} else {
  document.querySelector("#primary-download span").textContent = "查看客户端下载";
}
function locateDownload() {
  // Keep existing shared links useful on phones. Explicit platform links always win.
  if (os && location.hash === "#downloads" && (os === "android" || matchMedia("(max-width: 800px)").matches)) {
    history.replaceState(null, "", `#download-${os}`);
  }
  const platform = /^#download-(windows|macos|android)$/.exec(location.hash)?.[1];
  if (!platform) return;
  requestAnimationFrame(() => document.getElementById(`download-${platform}`)?.scrollIntoView({block:"start",behavior:"instant"}));
}
window.addEventListener("hashchange", locateDownload);
window.addEventListener("load", locateDownload, {once:true});
locateDownload();
let toastTimer;
function notify(message) {
  const toast = document.querySelector(".toast");
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.classList.add("visible");
  toastTimer = setTimeout(() => toast.classList.remove("visible"), 4000);
}
document.querySelectorAll("[data-copy]").forEach((button) =>
  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(button.dataset.copy);
      notify("SHA-256 已复制");
    } catch {
      const code = button.parentElement.querySelector("code");
      const range = document.createRange();
      range.selectNodeContents(code);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      notify("请复制已选中的 SHA-256 文本");
    }
  }),
);

// Language/time zone only suggest the optional route; never infer location or redirect downloads.
let chineseDownloadHint = (navigator.languages || [navigator.language]).some(language => /^zh(?:-|$)/i.test(language || ""));
try { chineseDownloadHint ||= ["Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "Asia/Urumqi"].includes(Intl.DateTimeFormat().resolvedOptions().timeZone); } catch { /* All acceleration links remain available. */ }
if (chineseDownloadHint) document.querySelectorAll(".accelerated-download").forEach(entry => entry.classList.add("suggested"));
