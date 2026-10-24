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
    image: "android-assets.png",
    caption: "Android · 资产库实际界面 / 模拟器 · 示例文件",
    alt: "Android 客户端资产库实际截图：独立账号下的 PDF、PNG 和 HTML 示例文件，带搜索、导入和交给 GPT 入口；采集于 Android 15 模拟器。",
    width: 1080,
    height: 2400,
    kind: "ANDROID · ACTUAL APP / DEMO FILES",
  },
};
const androidSkills = {
  ...platforms.android,
  image: "android-skills.png",
  caption: "Android · 120 个 Skill 实际界面 / 模拟器 · 本地示例账号",
  alt: "Android 客户端能力库实际截图：120 个项目 Skill、原生搜索与任务说明；采集于 Android 15 模拟器，尚无预置智能体。",
};
const tabs = [...document.querySelectorAll("[data-platform]")];
const image = document.querySelector("#product-image");
function selectPlatform(name, view = "assets") {
  const entry = name === "android" && view === "skills" ? androidSkills : platforms[name];
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
}
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
