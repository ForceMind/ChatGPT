"use strict";
const platforms = {
  windows: {
    image: "windows-installed.png",
    caption: "Windows · alpha.11 已安装客户端 / 未登录 ChatGPT",
    alt: "Windows 已安装客户端实际截图：未登录 ChatGPT 页面、账号名称、标签页与原生窗口按钮，没有地址栏。",
    width: 1040,
    height: 728,
  },
  macos: {
    image: "macos-window.png",
    caption: "macOS · 普通窗口实际截图 / 未登录 ChatGPT",
    alt: "macOS 客户端普通窗口实际截图：原生窗口按钮、未登录 ChatGPT、顶部标签与账号菜单，没有地址栏或远程控制标记。",
    width: 2720,
    height: 1800,
  },
  android: {
    image: null,
    caption: "Android · 主界面截图待更新 / APK 可下载",
  },
};
const tabs = [...document.querySelectorAll("[data-platform]")];
const image = document.querySelector("#product-image");
function selectPlatform(name) {
  const entry = platforms[name];
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
  document.querySelector("#product-capture-kind").textContent = entry.image
    ? "ACTUAL APP CAPTURE" : "ANDROID · PREVIEW PENDING";
  if (entry.image) {
    image.src = `/assets/${entry.image}`;
    image.alt = entry.alt;
    image.width = entry.width;
    image.height = entry.height;
  }
  image.classList.toggle("android", name === "android");
  document.querySelector("#product-caption").textContent = entry.caption;
}
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
