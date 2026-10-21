"use strict";

const icons = {
  chat: '<path d="M20 15a3 3 0 0 1-3 3H9l-5 4v-4a3 3 0 0 1-2-3V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3Z"/><path d="M7 8h8M7 12h5"/>',
  folder:
    '<path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>',
  sparkles:
    '<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5ZM20 2v4M18 4h4"/>',
  chevron: '<path d="m7 10 5 5 5-5"/>',
  agents:
    '<circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2M17 5a3 3 0 0 1 0 6m2 9v-2a6 6 0 0 0-2-4"/>',
  image:
    '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8" cy="8" r="1.5"/><path d="m3 17 6-6 5 5 3-3 4 4"/>',
  web: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M6 6.5h.01m3 0h.01m3 0h.01"/>',
  document:
    '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9ZM14 3v6h6M8 13h8M8 17h6"/>',
  table:
    '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M3 15h18M9 3v18M15 3v18"/>',
  star: '<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9Z"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
  convert: '<path d="M5 8h14m-4-4 4 4-4 4M19 16H5m4-4-4 4 4 4"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  list: '<path d="M8 5h13M8 12h13M8 19h13M3 5h.01M3 12h.01M3 19h.01"/>',
  search: '<circle cx="10" cy="10" r="6.5"/><path d="m15 15 5.5 5.5"/>',
  plus: '<path d="M12 4v16M4 12h16"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6ZM8 12l3 3 5-6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  expand: '<path d="M8 3H3v5M16 3h5v5M3 16v5h5M21 16v5h-5"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  external: '<path d="m5 19 14-14M7 5h12v12"/>',
  edit: '<path d="m16 3 5 5L9 20l-6 1 1-6ZM13 6l5 5"/>',
  book: '<path d="M12 5C8 2 5 2 2 3v16c3-1 6-1 10 2 4-3 7-3 10-2V3c-3-1-6-1-10 2Zm0 0v16"/>',
  code: '<path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18"/>',
  layout:
    '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 9v12"/>',
  send: '<path d="m22 2-7 20-4-9-9-4Zm0 0L11 13"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/>',
  restore: '<path d="M3 10a9 9 0 1 1 1 8M3 4v6h6M12 7v5l3 2"/>',
  tag: '<path d="m21 13-8 8L2 10V2h8ZM6 6h.01"/>',
};
const icon = (name) =>
  `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.document}</svg>`;
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const $ = (selector) => document.querySelector(selector);
const people = [
  { id: "work", name: "工作账号", email: "work@example.com", color: "#5275a0" },
  {
    id: "personal",
    name: "个人账号",
    email: "personal@example.com",
    color: "#32745d",
  },
];
const htmlSample = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>*{box-sizing:border-box}body{margin:0;background:#faf9f5;color:#343e30;font-family:Georgia,'PingFang SC',serif}nav{display:flex;justify-content:space-between;padding:22px 7%;border-bottom:1px solid #e5e5db;font:11px sans-serif}main{padding:60px 10%;max-width:800px}small{font:9px sans-serif;letter-spacing:2px;color:#929887}h1{font-size:52px;font-weight:400;line-height:1.15;margin:22px 0}p{font:13px/1.9 sans-serif;color:#7b8372;max-width:380px}button{background:#3e4937;color:#fff;border:0;padding:13px 19px;margin-top:18px;font:11px sans-serif;border-radius:2px}footer{border-top:1px solid #e5e5db;margin-top:45px;padding-top:17px;font:9px sans-serif;color:#a0a391}</style></head><body><nav><strong>STILL STUDIO</strong><span>About &nbsp; Work &nbsp; Contact</span></nav><main><small>A SPACE FOR YOUR IDEAS</small><h1>Make room<br>for better work.</h1><p>为专注留下空间。整理想法、连接灵感，让每一次开始都轻一点。</p><button>Explore your space →</button><footer>界面预览示例 · 此 HTML 不连接任何外部服务</footer></main></body></html>`;
const csvSample =
  "渠道,访问量,注册量,转化率\n自然搜索,2840,192,6.76%\n内容社区,1630,108,6.63%\n邮件触达,920,86,9.35%\n社交广告,2180,95,4.36%\n直接访问,1240,103,8.31%";
const jsonSample = JSON.stringify(
  {
    project: "Still Studio",
    version: "0.1",
    theme: { background: "#faf9f5", foreground: "#343e30" },
    sections: ["hero", "features", "stories", "contact"],
    status: "draft",
  },
  null,
  2,
);
const mdSample =
  "# 产品发布需求\n\n## 目标\n让不同账号拥有独立的文件和工作上下文。\n\n## 本轮范围\n- 资产归档、预览与整理\n- 可追溯的格式转换\n- 选择 Skill 与专业角色\n\n## 验收\n1. 原始文件得到保留\n2. 账号归属始终清楚\n3. 失败能够恢复\n\n> 这是一份用于 UI 预览的示例文件。";
const assets = [
  {
    id: "a1",
    profile: "work",
    name: "still-studio-landing.html",
    kind: "web",
    ext: "HTML",
    bytes: 32768,
    time: "10:32",
    order: 12,
    tags: ["落地页", "产品设计"],
    conversation: "落地页方案",
    text: htmlSample,
    favorite: true,
  },
  {
    id: "a2",
    profile: "work",
    name: "首页封面.png",
    kind: "image",
    ext: "PNG",
    bytes: 430080,
    time: "10:28",
    order: 11,
    tags: ["视觉素材", "封面"],
    conversation: "落地页方案",
    image: "assets/editorial-cover.svg",
    dimensions: "1920 × 1280",
    favorite: true,
  },
  {
    id: "a3",
    profile: "work",
    name: "产品发布简报.pdf",
    kind: "document",
    ext: "PDF",
    bytes: 1258291,
    time: "10:15",
    order: 10,
    tags: ["产品", "方案"],
    conversation: "产品发布计划",
    text: mdSample,
    pages: 8,
  },
  {
    id: "a4",
    profile: "work",
    name: "数据面板设计.png",
    kind: "image",
    ext: "PNG",
    bytes: 327680,
    time: "09:58",
    order: 9,
    tags: ["UI", "设计参考"],
    conversation: "数据面板设计",
    image: "assets/dashboard.svg",
    dimensions: "1440 × 960",
  },
  {
    id: "a5",
    profile: "work",
    name: "渠道表现分析.csv",
    kind: "data",
    ext: "CSV",
    bytes: 8602,
    time: "09:52",
    order: 8,
    tags: ["增长", "数据"],
    conversation: "本周增长分析",
    text: csvSample,
  },
  {
    id: "a6",
    profile: "work",
    name: "产品需求清单.md",
    kind: "document",
    ext: "MD",
    bytes: 13107,
    time: "09:47",
    order: 7,
    tags: ["产品", "需求"],
    conversation: "产品发布计划",
    text: mdSample,
    favorite: true,
  },
  {
    id: "a7",
    profile: "work",
    name: "页面配置.json",
    kind: "data",
    ext: "JSON",
    bytes: 4710,
    time: "09:43",
    order: 6,
    tags: ["开发", "配置"],
    conversation: "落地页方案",
    text: jsonSample,
  },
  {
    id: "a8",
    profile: "work",
    name: "品牌海报.webp",
    kind: "image",
    ext: "WEBP",
    bytes: 286720,
    time: "09:40",
    order: 5,
    tags: ["品牌", "视觉素材"],
    conversation: "品牌视觉方向",
    image: "assets/poster.svg",
    dimensions: "1600 × 1067",
  },
  {
    id: "a9",
    profile: "work",
    name: "still-studio-preview.pdf",
    kind: "document",
    ext: "PDF",
    bytes: 225280,
    time: "09:35",
    order: 4,
    tags: ["转换副本", "网页"],
    conversation: "落地页方案",
    text: mdSample,
    pages: 3,
    derivedFrom: "a1",
  },
  {
    id: "a10",
    profile: "work",
    name: "内容灵感记录.txt",
    kind: "document",
    ext: "TXT",
    bytes: 2150,
    time: "昨天",
    order: 3,
    tags: ["内容", "灵感"],
    conversation: "内容选题规划",
    text: "内容灵感记录\n\n1. 一个账号，一个清楚的上下文。\n2. 文件有归处，思路不打断。\n3. 熟悉的网页，清楚的工作空间。\n\n以上为交互预览示例。",
  },
  {
    id: "a11",
    profile: "work",
    name: "界面图标.svg",
    kind: "image",
    ext: "SVG",
    bytes: 5018,
    time: "昨天",
    order: 2,
    tags: ["UI", "图标"],
    conversation: "组件细节设计",
    image: "assets/dashboard.svg",
    dimensions: "720 × 480",
  },
  {
    id: "a12",
    profile: "work",
    name: "onboarding-flow.html",
    kind: "web",
    ext: "HTML",
    bytes: 18432,
    time: "昨天",
    order: 1,
    tags: ["交互", "网页"],
    conversation: "新用户引导",
    text: htmlSample.replace(
      "Make room<br>for better work.",
      "A thoughtful<br>first step.",
    ),
  },
  {
    id: "p1",
    profile: "personal",
    name: "周末旅行清单.md",
    kind: "document",
    ext: "MD",
    bytes: 8210,
    time: "11:05",
    order: 4,
    tags: ["个人", "旅行"],
    conversation: "周末安排",
    text: "# 周末旅行\n\n- 整理路线\n- 核对天气\n- 准备相机\n\n这是个人账号的示例文件。",
  },
  {
    id: "p2",
    profile: "personal",
    name: "阅读封面.png",
    kind: "image",
    ext: "PNG",
    bytes: 312200,
    time: "10:10",
    order: 3,
    tags: ["个人", "阅读"],
    conversation: "阅读计划",
    image: "assets/editorial-cover.svg",
    dimensions: "1920 × 1280",
  },
  {
    id: "p3",
    profile: "personal",
    name: "书单.json",
    kind: "data",
    ext: "JSON",
    bytes: 1804,
    time: "昨天",
    order: 2,
    tags: ["个人", "书单"],
    conversation: "阅读计划",
    text: '{\n  "books": ["设计中的设计", "思考，快与慢"],\n  "status": "planned"\n}',
    favorite: true,
  },
  {
    id: "p4",
    profile: "personal",
    name: "个人笔记.html",
    kind: "web",
    ext: "HTML",
    bytes: 15200,
    time: "昨天",
    order: 1,
    tags: ["个人", "笔记"],
    conversation: "笔记整理",
    text: htmlSample.replace("STILL STUDIO", "PERSONAL NOTES"),
  },
];
const state = {
  profile: "work",
  view: "assets",
  filter: "all",
  query: "",
  sort: "recent",
  layout: "grid",
  selected: "a1",
  checked: new Set(),
  libraryKind: "skills",
  libraryFilter: "all",
  libraryQuery: "",
  libraryFavorites: false,
  librarySelected: null,
  favoriteSkills: new Set(["skill-001", "skill-026", "skill-044"]),
  favoriteAgents: new Set(),
  taskSkill: null,
  taskAgent: null,
  taskPreview: null,
};
const catalog = window.PREVIEW_CATALOG;
const objectURLs = new Set();
let toastTimer,
  modalFocus,
  modalToken = 0;
const owner = () => people.find((p) => p.id === state.profile);
const size = (n) =>
  n >= 1048576
    ? `${(n / 1048576).toFixed(1)} MB`
    : `${(n / 1024).toFixed(1)} KB`;
const currentAsset = () =>
  assets.find((a) => a.id === state.selected && a.profile === state.profile);
const activeAssets = () => assets.filter((a) => a.profile === state.profile);
const categories = [
  { id: "all", name: "全部资产", icon: "grid" },
  { id: "image", name: "图片", icon: "image" },
  { id: "web", name: "网页", icon: "web" },
  { id: "document", name: "文档", icon: "document" },
  { id: "data", name: "数据与代码", icon: "code" },
  { id: "favorite", name: "收藏", icon: "star" },
  { id: "converted", name: "最近转换", icon: "convert" },
  { id: "trash", name: "回收站", icon: "trash" },
];
function matchesFilter(a, filter = state.filter) {
  if (filter === "trash") return !!a.trashed;
  if (a.trashed) return false;
  if (filter === "all") return true;
  if (filter === "favorite") return !!a.favorite;
  if (filter === "converted") return !!a.derivedFrom;
  return a.kind === filter;
}
function filteredAssets() {
  return activeAssets()
    .filter(
      (a) =>
        matchesFilter(a) &&
        `${a.name} ${a.tags.join(" ")} ${a.conversation}`
          .toLowerCase()
          .includes(state.query.toLowerCase()),
    )
    .sort((a, b) =>
      state.sort === "name"
        ? a.name.localeCompare(b.name, "zh-CN")
        : state.sort === "size"
          ? b.bytes - a.bytes
          : b.order - a.order,
    );
}
function notify(message) {
  clearTimeout(toastTimer);
  $("#toast").innerHTML = icon("check") + `<span>${esc(message)}</span>`;
  $("#toast").hidden = false;
  toastTimer = setTimeout(() => ($("#toast").hidden = true), 4200);
}
function hydrateIcons(root = document) {
  root
    .querySelectorAll("[data-icon]")
    .forEach((e) => (e.innerHTML = icon(e.dataset.icon)));
}
function showView(view) {
  state.view = view;
  $("#workbench").hidden = view !== "assets";
  $("#capability-page").hidden = !["skills", "agents"].includes(view);
  $("#chat-page").hidden = view !== "chat";
  $("#asset-toggle").classList.toggle("active", view === "assets");
  $("#library-toggle").classList.toggle(
    "active",
    ["skills", "agents"].includes(view),
  );
  $("#account-menu").hidden = true;
  $("#account-button").setAttribute("aria-expanded", "false");
  if (view === "assets") renderAssets();
  if (view === "skills" || view === "agents") {
    if (state.libraryKind !== view) {
      state.libraryFilter = "all";
      state.libraryQuery = "";
      state.librarySelected = null;
      state.libraryFavorites = false;
      $("#capability-search").value = "";
    }
    state.libraryKind = view;
    renderLibrary();
  }
  history.replaceState(null, "", `#${view}`);
}
function renderAccount() {
  const p = owner();
  $("#account-label").textContent = p.name;
  $(".account-button .account-dot").style.background = p.color;
  $("#sidebar-account").textContent = `${p.name}的本机文件`;
  $("#chat-account-hint").textContent = `${p.name} · 独立空间`;
  $("#account-menu").innerHTML =
    "<p>账号空间 · 预览备注</p>" +
    people
      .map(
        (p) =>
          `<button class="account-option ${p.id === state.profile ? "active" : ""}" role="menuitemradio" aria-checked="${p.id === state.profile}" data-profile="${p.id}"><span class="account-dot-small" style="background:${p.color}"></span><div>${esc(p.name)}<small>${esc(p.email)} · 示例备注</small></div><span>${p.id === state.profile ? "✓" : ""}</span></button>`,
      )
      .join("");
  $(".breadcrumb").innerHTML = `${esc(p.name)} <span>/</span> 资产`;
}
function thumb(a) {
  if (a.image)
    return `<img src="${esc(a.image)}" alt="${esc(a.name)}的预览示意" loading="lazy">`;
  if (a.kind === "web")
    return '<div class="file-preview web"><div class="mini-browser-bar"><i></i><i></i><i></i></div><div class="mini-web-body"><small>STILL STUDIO</small><strong>Make room<br>for better work.</strong><b>EXPLORE YOUR SPACE</b></div></div>';
  if (a.ext === "CSV")
    return `<div class="file-preview table">${csvSample
      .split("\n")
      .map(
        (r) =>
          '<div class="mini-table-row">' +
          r
            .split(",")
            .map((c) => `<span>${esc(c)}</span>`)
            .join("") +
          "</div>",
      )
      .join("")}</div>`;
  if (a.ext === "JSON")
    return `<pre class="mini-code">${esc((a.text || jsonSample).slice(0, 270))}</pre>`;
  return `<div class="file-preview doc"><div class="mini-doc-title">${esc(a.name.split(".")[0])}</div><div class="mini-doc-line"></div><div class="mini-doc-line"></div><div class="mini-doc-line short"></div><div class="mini-doc-block"></div><div class="mini-doc-line"></div></div>`;
}
function assetCard(a) {
  return `<article class="asset-card ${a.id === state.selected ? "selected" : ""} ${state.checked.has(a.id) ? "checked" : ""}" tabindex="0" role="button" aria-label="查看 ${esc(a.name)}" data-asset="${a.id}"><button class="card-check" data-check="${a.id}" aria-label="${state.checked.has(a.id) ? "取消选择" : "选择"} ${esc(a.name)}" aria-pressed="${state.checked.has(a.id)}">${state.checked.has(a.id) ? icon("check") : ""}</button><button class="card-star ${a.favorite ? "is-favorite" : ""}" data-favorite="${a.id}" aria-label="${a.favorite ? "取消收藏" : "收藏"} ${esc(a.name)}" aria-pressed="${!!a.favorite}">${icon("star")}</button><div class="asset-thumb">${thumb(a)}${a.derivedFrom ? '<span class="derived-marker">转换副本' + (a.demoConverted ? " · 示意" : "") + "</span>" : ""}</div><div class="asset-card-info"><div class="asset-card-name">${esc(a.name)}</div><div class="file-meta"><span class="type-label">${esc(a.ext)}</span><span>${size(a.bytes)}</span><span class="file-time">${esc(a.time)}</span></div></div></article>`;
}
function renderAssets() {
  const visible = filteredAssets();
  if (!visible.some((a) => a.id === state.selected))
    state.selected = visible[0]?.id || null;
  const all = activeAssets();
  $("#asset-navigation").innerHTML = categories
    .map(
      (c) =>
        `<button class="nav-item ${state.filter === c.id ? "active" : ""}" data-filter="${c.id}" aria-current="${state.filter === c.id ? "page" : "false"}"><span class="inline-icon">${icon(c.icon)}</span>${c.name}<span>${all.filter((a) => matchesFilter(a, c.id)).length}</span></button>`,
    )
    .join("");
  $("#asset-title").innerHTML =
    `${categories.find((c) => c.id === state.filter).name} <span>${visible.length}</span>`;
  $("#asset-context").innerHTML =
    `<span class="source-scope"><span class="inline-icon">${icon("shield")}</span>仅显示${esc(owner().name)}的文件</span><p>${state.filter === "trash" ? "可恢复到原账号的资产列表" : "下载、导入和转换副本统一整理"}</p>`;
  $("#asset-grid").className =
    `asset-grid ${state.layout === "list" ? "list-mode" : ""}`;
  $("#asset-grid").innerHTML = visible.length
    ? visible.map(assetCard).join("")
    : `<div class="empty-state">${icon(state.filter === "trash" ? "trash" : "search")}<h3>${state.query ? "没有找到匹配的资产" : state.filter === "trash" ? "回收站是空的" : "这里暂时没有文件"}</h3><p>${state.query ? "试试文件名、标签或来源对话。" : "你可以下载或导入文件，按账号整理。"}</p>${state.query ? '<button class="secondary" data-action="clear-search">清除搜索</button>' : '<button class="secondary" data-action="import">导入文件</button>'}</div>`;
  $("#asset-status").innerHTML =
    `<span>${visible.length} 个${state.query ? "匹配的" : ""}资产 · ${esc(owner().name)}</span><span>示例操作仅保留在本页</span>`;
  const ids = [...state.checked].filter((id) =>
    all.some((a) => a.id === id && matchesFilter(a)),
  );
  state.checked = new Set(ids);
  $("#selection-bar").hidden = !ids.length;
  $("#selection-bar").innerHTML =
    `<strong>已选择 ${ids.length} 项</strong><button data-action="send-selected">交给 GPT</button><button data-action="convert-selected">转换格式</button><button data-action="trash-selected">${state.filter === "trash" ? "恢复资产" : "移入回收站"}</button><button data-action="clear-selection">取消选择</button>`;
  document
    .querySelectorAll("[data-view]")
    .forEach((b) =>
      b.classList.toggle("active", b.dataset.view === state.layout),
    );
  renderDetail();
}
function renderDetail() {
  const a = currentAsset();
  if (!a) {
    $("#asset-detail").innerHTML =
      `<div class="empty-detail">${icon("document")}<p>选择一个文件<br>查看预览、来源和可用操作</p></div>`;
    return;
  }
  const parent = assets.find((x) => x.id === a.derivedFrom);
  $("#asset-detail").innerHTML =
    `<div class="detail-topline"><span>文件详情</span><button class="icon-button" data-action="preview" aria-label="展开预览">${icon("expand")}</button></div><div class="detail-preview" role="button" tabindex="0" data-action="preview" aria-label="预览 ${esc(a.name)}">${thumb(a)}<span class="preview-expand">${icon("expand")}</span></div><h3 class="detail-name">${esc(a.name)}</h3><div class="detail-tags">${a.tags.map((t) => `<span class="tag">${esc(t)}</span>`).join("")}</div>${a.trashed ? '<button class="primary" data-action="restore-current">恢复这个文件</button>' : `<div class="detail-primary-actions"><button class="primary" data-action="send-current"><span class="inline-icon">${icon("sparkles")}</span>交给 GPT</button><button class="secondary" data-action="convert-current"><span class="inline-icon">${icon("convert")}</span>转换格式</button></div>`}<div class="detail-properties"><div class="property-row"><span>格式</span><span>${esc(a.ext)}${a.demoConverted ? " · 转换示意" : ""}</span></div><div class="property-row"><span>大小</span><span>${size(a.bytes)}${a.imported ? "" : " · 示例"}</span></div>${a.dimensions ? `<div class="property-row"><span>尺寸</span><span>${esc(a.dimensions)} · 示例</span></div>` : ""}<div class="property-row"><span>所属账号</span><span><span class="account-dot-small" style="background:${owner().color}"></span> ${esc(owner().name)}</span></div><div class="property-row"><span>来源对话</span><span><button class="source-link" data-action="source">${esc(a.conversation)} ↗</button></span></div><div class="property-row"><span>添加时间</span><span>${a.time === "昨天" ? "昨天" : `今天 ${esc(a.time)}`}</span></div>${parent ? `<div class="property-row"><span>派生自</span><span><button class="source-link" data-parent="${parent.id}">${esc(parent.name)}</button></span></div>` : ""}</div><div class="detail-section-title">整理标记<button data-action="edit-tags">编辑</button></div><div class="detail-tags">${a.tags.map((t) => `<span class="tag">${esc(t)}</span>`).join("") || '<span class="tag">尚未添加标签</span>'}</div><div class="detail-section-title">文件动态</div><div class="activity-line"><span class="activity-dot"></span><div>${a.demoConverted ? "添加转换结果示意" : a.imported ? "本页导入预览" : "从 ChatGPT 下载 · 示例"}<small>${esc(a.time)} · ${esc(owner().name)}</small></div></div>${parent ? '<div class="activity-line"><span class="activity-dot"></span><div>原始文件保留<small>副本与原件之间保留关联</small></div></div>' : ""}<div class="detail-small-actions"><button data-action="rename">${icon("edit")}重命名</button><button data-action="reveal">${icon("folder")}在访达显示</button><button data-action="trash-current">${icon(a.trashed ? "restore" : "trash")}${a.trashed ? "恢复" : "移至回收站"}</button></div>`;
}
function openModal(title, description, content, footer = "", extraClass = "") {
  modalToken++;
  modalFocus = document.activeElement;
  $("#modal-root").innerHTML =
    `<div class="modal-backdrop"><section class="modal ${extraClass}" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="modal-header"><div><h2>${esc(title)}</h2><p>${esc(description)}</p></div><button class="icon-button" data-action="close-modal" aria-label="关闭对话框">${icon("close")}</button></div><div class="modal-content">${content}</div>${footer ? `<div class="modal-footer">${footer}</div>` : ""}</section></div>`;
  requestAnimationFrame(() => {
    const field = $(".modal [autofocus]") || $(".modal input, .modal select, .modal textarea") || $(".modal button");
    field?.focus();
  });
}
function closeModal() {
  modalToken++;
  $("#modal-root").innerHTML = "";
  if (modalFocus?.isConnected) modalFocus.focus();
}
function safeHTML(value) {
  const parsed = new DOMParser().parseFromString(value, "text/html");
  parsed
    .querySelectorAll("base,meta[http-equiv],iframe,object,embed")
    .forEach((n) => n.remove());
  const csp = parsed.createElement("meta");
  csp.httpEquiv = "Content-Security-Policy";
  csp.content =
    "default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; font-src 'none'; form-action 'none'; base-uri 'none'";
  parsed.head.prepend(csp);
  return "<!doctype html>" + parsed.documentElement.outerHTML;
}
function openPreview(a, mode = "preview") {
  if (!a) return;
  const web = a.kind === "web";
  const textModes = web || ["JSON", "MD", "TXT", "CSV"].includes(a.ext);
  const content = `${textModes ? `<div class="viewer-tabs"><button data-preview-mode="preview" class="${mode === "preview" ? "active" : ""}">${web ? "网页预览" : a.ext === "CSV" ? "表格视图" : "阅读视图"}</button><button data-preview-mode="source" class="${mode === "source" ? "active" : ""}">源文件</button></div>` : ""}<div class="preview-viewer" id="preview-viewer"></div><p class="viewer-caption">${a.imported ? "仅查看你主动选择的本页文件，不会上传。" : a.ext === "PDF" ? "PDF 分页界面示意；本预览未接入真实 PDF 解析。" : "当前为交互预览示例文件。"}${web ? " HTML 在限制脚本、网络和外部文件访问的沙箱中显示。" : ""}</p>`;
  openModal(
    a.name,
    `${a.ext} · ${owner().name} · ${size(a.bytes)}`,
    content,
    `<span>${a.imported ? "本页导入文件" : "示例资产"}</span><button class="secondary" data-action="convert-current">转换格式</button><button class="primary" data-action="send-current">交给 GPT</button>`,
    "preview-modal",
  );
  const viewer = $("#preview-viewer");
  if (mode === "source")
    viewer.innerHTML = `<pre>${esc(a.text || "此示例使用缩略图展示，没有对应的源文件。")}</pre>`;
  else if (a.image)
    viewer.innerHTML = `<img src="${esc(a.image)}" alt="${esc(a.name)}">`;
  else if (web) {
    viewer.innerHTML = `<iframe sandbox="" title="${esc(a.name)}的安全预览"></iframe>`;
    viewer.querySelector("iframe").srcdoc = safeHTML(a.text || htmlSample);
  } else if (a.ext === "CSV") {
    viewer.innerHTML =
      "<table>" +
      a.text
        .split("\n")
        .map(
          (r, i) =>
            "<tr>" +
            r
              .split(",")
              .map((c) => `<${i ? "td" : "th"}>${esc(c)}</${i ? "td" : "th"}>`)
              .join("") +
            "</tr>",
        )
        .join("") +
      "</table>";
  } else if (a.ext === "PDF") {
    viewer.innerHTML = `<pre>第 1 页 / ${a.pages || 1} 页（布局示意）\n\n${esc(a.text || "真实 PDF 解析将在原生资产预览模块中接入。")}\n\n—— 此处没有解析或重写你的 PDF 原文件 ——</pre>`;
  } else
    viewer.innerHTML = `<pre>${esc(a.text || "暂时没有可显示的文本。")}</pre>`;
}
function convertOptions(a) {
  if (a.kind === "web") return ["PDF", "PNG"];
  if (a.ext === "MD") return ["HTML", "PDF", "TXT"];
  if (a.ext === "PDF") return ["PNG", "JPEG"];
  if (a.ext === "CSV") return ["JSON"];
  if (a.ext === "JSON") return ["CSV"];
  if (a.ext === "SVG") return ["PNG"];
  if (a.kind === "image")
    return ["PNG", "JPEG", "WEBP"].filter((x) => x !== a.ext);
  return ["MD"];
}
function openConvert(files) {
  files = files.filter((a) => !a.trashed);
  if (!files.length) return;
  const choices = convertOptions(files[0]).filter((x) =>
    files.every((a) => convertOptions(a).includes(x)),
  );
  if (!choices.length) {
    notify("所选文件没有共同的转换格式，请按类型分别选择。");
    return;
  }
  let selected = choices[0];
  const content = `<div class="file-chips">${files.map((a) => `<div class="file-chip"><span class="inline-icon">${icon(a.kind === "image" ? "image" : "document")}</span>${esc(a.name)}<small>${esc(a.ext)}</small></div>`).join("")}</div><label class="field-label">转换为</label><div class="format-options">${choices.map((f, i) => `<button class="format-option ${i ? "" : "active"}" data-format="${f}">${f}<small>${f === "PDF" ? "可分享的文档" : f === "PNG" || f === "JPEG" || f === "WEBP" ? "图片副本" : "结构化副本"}</small></button>`).join("")}</div><div class="field-row"><div><label class="field-label" for="output-name">输出名称</label><input class="field-input" id="output-name" value="${esc(files.length === 1 ? files[0].name.replace(/\.[^.]+$/, "") : "转换副本")}" maxlength="80"></div><div><label class="field-label">保存方式</label><select class="field-select" disabled><option>保存为新资产，保留原件</option></select></div></div><div class="convert-preview-path" id="convert-path"></div><div class="modal-note">${icon("shield")}<span>这是转换流程预览。确认后只添加一个清楚标记的结果示意，不会转换或写入真实文件。</span></div>`;
  openModal(
    "转换格式",
    `为 ${files.length} 个文件创建独立副本，保留来源关系。`,
    content,
    '<button class="secondary" data-action="close-modal">取消</button><button class="primary" id="convert-demo">演示转换流程</button>',
  );
  const update = () =>
    ($("#convert-path").textContent =
      `${owner().name} / ${$("#output-name").value.trim() || "未命名"}.${selected.toLowerCase()}`);
  update();
  $("#output-name").addEventListener("input", update);
  $(".format-options").addEventListener("click", (e) => {
    const b = e.target.closest("[data-format]");
    if (!b) return;
    selected = b.dataset.format;
    document
      .querySelectorAll("[data-format]")
      .forEach((x) => x.classList.toggle("active", x === b));
    update();
  });
  $("#convert-demo").addEventListener("click", () => {
    const outputName = $("#output-name").value.trim() || "未命名";
    const targetProfile = state.profile;
    openModal(
      "转换流程预览",
      "演示转换状态与结果归档，不会执行真实格式转换。",
      `<div class="execution-steps">${["读取选中的源文件", "按所选格式生成新副本", "归档副本并关联原始文件"].map((t, i) => `<div class="execution-step" id="conversion-step-${i}"><span class="step-indicator">${i + 1}</span><div><strong>${t}</strong><small>流程示意</small></div></div>`).join("")}</div><div id="conversion-finish"></div>`,
      '<button class="secondary" data-action="close-modal">关闭</button>',
    );
    const token = modalToken;
    runSteps("conversion-step", 3, token, () => {
      files.forEach((a, i) =>
        assets.push({
          ...a,
          id: "demo-" + Date.now() + "-" + i,
          profile: targetProfile,
          name: `${files.length === 1 ? outputName : a.name.replace(/\.[^.]+$/, "")}.${selected.toLowerCase()}`,
          ext: selected,
          kind: ["PNG", "JPEG", "WEBP"].includes(selected)
            ? "image"
            : selected === "HTML"
              ? "web"
              : ["CSV", "JSON"].includes(selected)
                ? "data"
                : "document",
          derivedFrom: a.id,
          demoConverted: true,
          trashed: false,
          favorite: false,
          time: "刚刚",
          order: 100 + Date.now(),
          tags: ["转换副本", "演示结果"],
        }),
      );
      state.filter = "converted";
      state.query = "";
      $("#asset-search").value = "";
      state.selected = assets.at(-1).id;
      state.checked.clear();
      renderAssets();
      $("#conversion-finish").innerHTML =
        '<div class="execution-finish"><strong>已添加转换结果示意</strong>没有生成或写入真实文件。你可以查看副本与原件的关联。</div>';
      $(".modal-footer").innerHTML =
        '<button class="primary" data-action="finish-convert">查看结果示意</button>';
    });
  });
}
function runSteps(prefix, count, token, done) {
  let n = 0;
  function next() {
    if (token !== modalToken) return;
    for (let i = 0; i < count; i++) {
      const el = $(`#${prefix}-${i}`);
      el?.classList.toggle("active", i === n);
      el?.classList.toggle("complete", i < n);
      if (i < n && el)
        el.querySelector(".step-indicator").innerHTML = icon("check");
    }
    if (n === count) {
      done();
      return;
    }
    n++;
    setTimeout(next, 450);
  }
  next();
}
function selectTaskDefaults(files) {
  if (!state.taskSkill) {
    const a = files[0];
    const name =
      a?.kind === "web"
        ? "HTML 结构解读"
        : a?.kind === "image"
          ? "截图问题分析"
          : a?.kind === "data"
            ? "数据清洗方案"
            : "文档摘要";
    state.taskSkill = catalog.skills.find((s) => s.name === name)?.id;
  }
  return {
    skill: catalog.skills.find((s) => s.id === state.taskSkill),
    agent: catalog.agents.find((a) => a.id === state.taskAgent),
  };
}
function openTask(files) {
  files = files.filter((a) => !a.trashed);
  const { skill, agent } = selectTaskDefaults(files);
  const sourceProfile = state.profile;
  const options = (items, chosen) =>
    items
      .map(
        (item) =>
          `<option value="${item.id}" ${item.id === chosen ? "selected" : ""}>${esc(item.name)}</option>`,
      )
      .join("");
  const content = `<div class="file-chips">${files.length ? files.map((a) => `<div class="file-chip"><span class="inline-icon">${icon(a.kind === "image" ? "image" : "document")}</span>${esc(a.name)}<small>${esc(a.ext)}</small></div>`).join("") : '<div class="file-chip"><span class="inline-icon">' + icon("chat") + "</span>不附带文件，使用所选能力发起对话</div>"}</div><div class="field-row"><div><label class="field-label" for="target-account">使用账号</label><select id="target-account" class="field-select">${people.map((p) => `<option value="${p.id}" ${p.id === state.profile ? "selected" : ""}>${esc(p.name)}</option>`).join("")}</select></div><div><label class="field-label" for="task-language">输出语言</label><select id="task-language" class="field-select"><option>简体中文</option><option>English</option><option>跟随文件语言</option></select></div></div><div id="cross-account-area"></div><label class="field-label" for="task-skill">处理任务 · Skill</label><select id="task-skill" class="field-select"><option value="">不指定 Skill</option>${options(catalog.skills, skill?.id)}</select><label class="field-label" for="task-agent">专业角色 · 选填</label><select id="task-agent" class="field-select"><option value="">使用 ChatGPT 默认角色</option>${options(catalog.agents, agent?.id)}</select><label class="field-label" for="task-instructions">处理要求</label><textarea id="task-instructions" class="field-textarea" placeholder="例如：保留原始风格，改进移动端排版，并输出修改后的 HTML。" rows="3"></textarea><p class="task-source-hint">默认使用文件所属账号。选择其他账号时，需要明确确认跨账号使用。</p><div class="modal-note">${icon("shield")}<span>流程演示：不会创建真实对话、上传文件或发送消息。原生接入后，附件就绪才会发送；结果不确定时停止重试。</span></div>`;
  openModal(
    "交给 GPT",
    files.length
      ? `${files.length} 个文件 · 新建对话处理，保留来源关联。`
      : "使用所选 Skill 或专业角色开始一个新对话。",
    content,
    '<button class="secondary" data-action="close-modal">取消</button><button class="primary" id="task-demo"><span class="inline-icon">' +
      icon("send") +
      "</span>预览执行流程</button>",
  );
  $("#target-account").addEventListener("change", () => {
    const cross = $("#target-account").value !== sourceProfile && files.length;
    $("#cross-account-area").innerHTML = cross
      ? '<div class="cross-account-warning">这些文件来自' +
        esc(people.find((p) => p.id === sourceProfile).name) +
        '，将交给另一个账号处理。<label><input type="checkbox" id="cross-consent">我确认使用所选账号处理这些文件</label></div>'
      : "";
    $("#task-demo").disabled = !!cross;
    $("#cross-consent")?.addEventListener(
      "change",
      (e) => ($("#task-demo").disabled = !e.target.checked),
    );
  });
  $("#task-demo").addEventListener("click", () => {
    const target = people.find((p) => p.id === $("#target-account").value);
    const s = catalog.skills.find((s) => s.id === $("#task-skill").value);
    const ag = catalog.agents.find((a) => a.id === $("#task-agent").value);
    const request = {
      target,
      skill: s,
      agent: ag,
      files,
      instructions: $("#task-instructions").value,
      language: $("#task-language").value,
    };
    state.taskSkill = s?.id || null;
    state.taskAgent = ag?.id || null;
    openTaskProgress(request);
  });
}
function openTaskProgress(request) {
  const labels = [
    `在${request.target.name}中新建对话`,
    `准备上传 ${request.files.length} 个选中文件`,
    "附件就绪后提交任务",
  ];
  openModal(
    "交给 GPT · 流程预览",
    "以下为操作状态示意，不会向真实网页发出请求。",
    `<div class="execution-steps">${labels.map((text, i) => `<div class="execution-step" id="task-step-${i}"><span class="step-indicator">${i + 1}</span><div><strong>${esc(text)}</strong><small>${i === 0 ? "目标账号固定，不自动切换" : i === 1 ? "附件失败时保留任务并提示" : "发送结果不确定时停止重试"}</small></div></div>`).join("")}</div><div id="task-finish"></div>`,
    '<button class="secondary" data-action="close-modal">关闭预览</button>',
  );
  const token = modalToken;
  runSteps("task-step", 3, token, () => {
    state.taskPreview = request;
    $("#task-finish").innerHTML =
      `<div class="execution-finish"><strong>流程预览结束，尚未真实发送</strong>${esc(request.skill?.name || "自由对话")}${request.agent ? " · " + esc(request.agent.name) : ""}<br>没有创建真实对话或上传文件。下一阶段将接入网页，并验证这些状态。</div>`;
    $(".modal-footer").innerHTML =
      '<button class="secondary" data-action="close-modal">关闭</button><button class="primary" data-action="task-chat">查看对话示意</button>';
  });
}
function libraryItems() {
  return state.libraryKind === "skills" ? catalog.skills : catalog.agents;
}
function favoriteSet() {
  return state.libraryKind === "skills"
    ? state.favoriteSkills
    : state.favoriteAgents;
}
function libraryFiltered() {
  return libraryItems()
    .filter(
      (i) =>
        (state.libraryFilter === "all" ||
          (state.libraryFilter === "internet"
            ? i.internetPriority
            : i.category === state.libraryFilter)) &&
        (!state.libraryFavorites || favoriteSet().has(i.id)) &&
        `${i.name} ${i.description} ${i.category} ${i.sourcePath || ""}`
          .toLowerCase()
          .includes(state.libraryQuery.toLowerCase()),
    )
    .sort(
      (a, b) =>
        Number(b.internetPriority) - Number(a.internetPriority) ||
        Number(favoriteSet().has(b.id)) - Number(favoriteSet().has(a.id)),
    );
}
function renderLibrary() {
  const agent = state.libraryKind === "agents";
  const all = libraryItems();
  const visible = libraryFiltered();
  if (!visible.some((i) => i.id === state.librarySelected))
    state.librarySelected = visible[0]?.id || null;
  $("#skills-nav").classList.toggle("active", !agent);
  $("#agents-nav").classList.toggle("active", agent);
  $("#capability-title").innerHTML =
    `${agent ? "智能体" : "Skill"} <span>${all.length}</span>`;
  $("#capability-description").textContent = agent
    ? "选择专业角色，与任务和文件一起进入新的对话。"
    : "把一个处理目标，变成清楚的输入、步骤和输出。";
  $("#library-notice").innerHTML =
    `<span class="inline-icon">${icon(agent ? "agents" : "sparkles")}</span><span>${agent ? "当前没有预置智能体，可使用 Skill 和自定义处理要求。" : "120 个独立任务的目录与流程草案。当前为 UI 预览，尚未接入真实执行。"}</span>`;
  const cats = [...new Set(all.map((i) => i.category))];
  $("#capability-navigation").innerHTML = [
    { id: "all", name: "全部" + (agent ? "角色" : "Skill"), count: all.length },
    ...(agent ? [{ id: "internet", name: "互联网相关", count: all.filter(i => i.internetPriority).length }] : []),
    ...cats.map((c) => ({
      id: c,
      name: c,
      count: all.filter((i) => i.category === c).length,
    })),
  ]
    .map(
      (c) =>
        `<button class="nav-item ${state.libraryFilter === c.id ? "active" : ""}" data-library-filter="${esc(c.id)}" aria-current="${state.libraryFilter === c.id ? "page" : "false"}">${esc(c.name)}<span>${c.count}</span></button>`,
    )
    .join("");
  $("#favorites-only").classList.toggle("active", state.libraryFavorites);
  $("#favorites-only").setAttribute(
    "aria-pressed",
    String(state.libraryFavorites),
  );
  $("#library-result-heading").innerHTML =
    `<span>${visible.length} 个${agent ? "角色" : "任务"}${state.libraryQuery ? "匹配搜索" : ""}</span><span>${agent ? "互联网相关优先" : "按文件类型选择合适的任务"}</span>`;
  let lastGroup = true;
  $("#capability-grid").innerHTML = visible.length
    ? visible
        .map((item, index) => {
          const divider =
            agent && index > 0 && lastGroup && !item.internetPriority
              ? '<div class="section-break">通用与其他专业领域</div>'
              : "";
          lastGroup = item.internetPriority;
          return (
            divider +
            `<article class="capability-card ${state.librarySelected === item.id ? "selected" : ""}" tabindex="0" role="button" data-capability="${item.id}" data-priority="${!!item.internetPriority}" aria-label="查看 ${esc(item.name)}"><div class="capability-card-header"><span class="capability-symbol">${icon(agent ? "agents" : item.icon)}</span><button class="card-star ${favoriteSet().has(item.id) ? "is-favorite" : ""}" data-capability-favorite="${item.id}" aria-label="${favoriteSet().has(item.id) ? "取消收藏" : "收藏"} ${esc(item.name)}" aria-pressed="${favoriteSet().has(item.id)}">${icon("star")}</button></div><h3>${esc(item.name)}</h3><p>${esc(item.description)}</p><div class="capability-card-foot"><span class="tag">${esc(item.category)}</span>${agent && item.internetPriority ? '<span class="internet-tag">互联网相关</span>' : !agent ? `<span>${esc(item.inputs.slice(0, 2).join(" / "))}</span>` : ""}</div></article>`
          );
        })
        .join("")
    : `<div class="empty-state">${icon("search")}<h3>没有找到匹配的能力</h3><p>换一个名称、场景或关键词试试。</p><button class="secondary" data-action="clear-library-search">清除筛选</button></div>`;
  renderCapabilityDetail();
}
function renderCapabilityDetail() {
  const item = libraryItems().find((i) => i.id === state.librarySelected);
  if (!item) {
    $("#capability-detail").innerHTML =
      `<div class="empty-detail">${icon("book")}<p>选择一个任务或角色<br>查看用途与使用方式</p></div>`;
    return;
  }
  const agent = state.libraryKind === "agents";
  $("#capability-detail").innerHTML =
    `<div class="detail-topline"><span>${agent ? "智能体" : "Skill"}详情</span><span>${agent ? "角色定义" : "任务流程草案"}</span></div><div class="capability-symbol">${icon(agent ? "agents" : item.icon)}</div><h3>${esc(item.name)}</h3><div class="detail-tags"><span class="tag">${esc(item.category)}</span>${item.internetPriority ? '<span class="tag">互联网相关</span>' : ""}</div><p class="capability-description">${esc(item.description)}</p><button class="primary" data-action="use-capability"><span class="inline-icon">${icon("sparkles")}</span>${agent ? "选择此角色发起任务" : "选择此 Skill 发起任务"}</button>${agent ? `<div class="detail-field-label">在对话中使用</div><ol><li>选择需要处理的文件和任务</li><li>带入角色的专业要求与工作流程</li><li>按网页实际可用的工具完成处理</li></ol><div class="detail-field-label">能力边界</div><p class="capability-description">角色中提到的终端、MCP、部署等工具需要对应执行环境，导入角色不代表已接入这些能力。</p>` : `<div class="detail-field-label">适用文件</div><div>${item.inputs.map((t) => `<span class="tag">${esc(t)}</span>`).join("")}</div><div class="detail-field-label">可填写的参数</div><ul>${item.parameters.map((p) => `<li>${esc(p)}</li>`).join("")}</ul><div class="detail-field-label">处理流程</div><ol>${item.steps.map((s) => `<li>${esc(s)}</li>`).join("")}</ol><div class="detail-field-label">期望输出</div><p class="capability-description">${esc(item.output)}</p><div class="capability-source">此处为目录及流程草案。真实上传、提示词执行与输出校验在后续原生接入阶段完成。</div>`}`;
}
function renameOrTags(kind) {
  const a = currentAsset();
  if (!a) return;
  const tags = kind === "tags";
  openModal(
    tags ? "编辑整理标记" : "重命名文件",
    tags ? "用标签把相关文件联系起来。" : "修改显示名称，保留来源记录。",
    `<label class="field-label" for="edit-value">${tags ? "标签（逗号分隔）" : "文件名称"}</label><input id="edit-value" class="field-input" value="${esc(tags ? a.tags.join("，") : a.name)}" maxlength="160" autofocus><div class="modal-note">${icon("shield")}<span>交互预览仅修改本页示例，不改动本机文件。</span></div>`,
    '<button class="secondary" data-action="close-modal">取消</button><button class="primary" id="save-edit">保存</button>',
  );
  $("#save-edit").addEventListener("click", () => {
    const value = $("#edit-value").value.trim();
    if (!value) {
      notify("请输入名称或标签。");
      return;
    }
    if (tags)
      a.tags = value
        .split(/[,，]/)
        .map((s) => s.trim())
        .filter(Boolean)
        .slice(0, 8);
    else a.name = value;
    closeModal();
    renderAssets();
    notify(tags ? "整理标记已在预览中更新" : "示例文件名称已更新");
  });
}
function trashFiles(files) {
  if (!files.length) return;
  const restore = state.filter === "trash";
  files.forEach((a) => (a.trashed = !restore));
  state.checked.clear();
  renderAssets();
  notify(restore ? "示例文件已恢复到原账号" : "示例文件已移入回收站，可恢复");
}
function selectFiles() {
  return [...state.checked]
    .map((id) => assets.find((a) => a.id === id && a.profile === state.profile))
    .filter(Boolean);
}
function setChatSource(
  title = "有什么可以帮忙的？",
  subtitle = "文件有自己的位置，思路继续留在这里。",
) {
  $("#chat-title").textContent = title;
  $("#chat-subtitle").textContent = subtitle;
  showView("chat");
}
document.addEventListener("click", (event) => {
  const profile = event.target.closest("[data-profile]");
  if (profile) {
    state.profile = profile.dataset.profile;
    state.selected = null;
    state.checked.clear();
    state.filter = "all";
    state.query = "";
    $("#asset-search").value = "";
    renderAccount();
    renderAssets();
    $("#account-menu").hidden = true;
    $("#account-button").setAttribute("aria-expanded", "false");
    notify(`已切换到${owner().name}的示例空间`);
    return;
  }
  const fav = event.target.closest("[data-favorite]");
  if (fav) {
    const a = assets.find(
      (a) => a.id === fav.dataset.favorite && a.profile === state.profile,
    );
    if (a) {
      a.favorite = !a.favorite;
      renderAssets();
    }
    return;
  }
  const check = event.target.closest("[data-check]");
  if (check) {
    const id = check.dataset.check;
    state.checked.has(id) ? state.checked.delete(id) : state.checked.add(id);
    renderAssets();
    return;
  }
  const cf = event.target.closest("[data-capability-favorite]");
  if (cf) {
    const set = favoriteSet();
    const id = cf.dataset.capabilityFavorite;
    set.has(id) ? set.delete(id) : set.add(id);
    renderLibrary();
    return;
  }
  const card = event.target.closest("[data-asset]");
  if (card) {
    state.selected = card.dataset.asset;
    renderAssets();
    return;
  }
  const capability = event.target.closest("[data-capability]");
  if (capability) {
    state.librarySelected = capability.dataset.capability;
    renderLibrary();
    return;
  }
  const filter = event.target.closest("[data-filter]");
  if (filter) {
    state.filter = filter.dataset.filter;
    state.selected = null;
    state.checked.clear();
    renderAssets();
    return;
  }
  const libFilter = event.target.closest("[data-library-filter]");
  if (libFilter) {
    state.libraryFilter = libFilter.dataset.libraryFilter;
    state.librarySelected = null;
    renderLibrary();
    return;
  }
  const layout = event.target.closest("[data-view]");
  if (layout) {
    state.layout = layout.dataset.view;
    renderAssets();
    return;
  }
  const parent = event.target.closest("[data-parent]");
  if (parent) {
    state.filter = "all";
    state.query = "";
    $("#asset-search").value = "";
    state.selected = parent.dataset.parent;
    renderAssets();
    return;
  }
  const previewMode = event.target.closest("[data-preview-mode]");
  if (previewMode) {
    openPreview(currentAsset(), previewMode.dataset.previewMode);
    return;
  }
  const action = event.target.closest("[data-action]")?.dataset.action;
  if (!action) return;
  if (["assets", "skills", "agents", "chat"].includes(action)) {
    showView(action);
    return;
  }
  if (action === "close-modal") {
    closeModal();
    return;
  }
  if (action === "import") {
    $("#file-input").click();
    return;
  }
  if (action === "preview") {
    openPreview(currentAsset());
    return;
  }
  if (action === "clear-search") {
    state.query = "";
    $("#asset-search").value = "";
    renderAssets();
    return;
  }
  if (action === "clear-selection") {
    state.checked.clear();
    renderAssets();
    return;
  }
  if (action === "send-current") {
    const a = currentAsset();
    openTask(a ? [a] : []);
    return;
  }
  if (action === "send-selected") {
    openTask(selectFiles());
    return;
  }
  if (action === "convert-current") {
    const a = currentAsset();
    if (a) openConvert([a]);
    return;
  }
  if (action === "convert-selected") {
    openConvert(selectFiles());
    return;
  }
  if (action === "trash-selected") {
    trashFiles(selectFiles());
    return;
  }
  if (action === "trash-current" || action === "restore-current") {
    const a = currentAsset();
    if (a) trashFiles([a]);
    return;
  }
  if (action === "rename") {
    renameOrTags("name");
    return;
  }
  if (action === "edit-tags") {
    renameOrTags("tags");
    return;
  }
  if (action === "reveal") {
    notify("此为界面预览；原生接入后会在访达中定位文件。");
    return;
  }
  if (action === "source") {
    setChatSource(
      currentAsset()?.conversation || "落地页方案",
      "来源对话界面示意，不会打开或读取你的真实聊天记录。",
    );
    return;
  }
  if (action === "new-chat") {
    setChatSource();
    notify("新对话标签的界面示意，未新建真实对话。");
    return;
  }
  if (action === "demo-chat") {
    notify("预览不会发送消息。真实网页发送将在原生接入阶段验证。");
    return;
  }
  if (action === "clear-library-search") {
    state.libraryQuery = "";
    state.libraryFilter = "all";
    state.libraryFavorites = false;
    $("#capability-search").value = "";
    renderLibrary();
    return;
  }
  if (action === "use-capability") {
    if (state.libraryKind === "skills") state.taskSkill = state.librarySelected;
    else state.taskAgent = state.librarySelected;
    const a = currentAsset();
    openTask(a && !a.trashed ? [a] : []);
    return;
  }
  if (action === "finish-convert") {
    closeModal();
    showView("assets");
    return;
  }
  if (action === "task-chat") {
    const task = state.taskPreview;
    closeModal();
    if (task && task.target.id !== state.profile) {
      state.profile = task.target.id;
      state.selected = null;
      state.checked.clear();
      state.filter = "all";
      renderAccount();
    }
    setChatSource(
      task?.skill?.name || "新任务对话",
      "对话示意 · 实装后会在选定账号上传文件并发起处理。",
    );
    return;
  }
});
$("#account-button").addEventListener("click", () => {
  const open = $("#account-menu").hidden;
  $("#account-menu").hidden = !open;
  $("#account-button").setAttribute("aria-expanded", String(open));
  if (open) $("#account-menu button")?.focus();
});
document.addEventListener("click", (e) => {
  if (!e.target.closest(".account-anchor")) {
    $("#account-menu").hidden = true;
    $("#account-button").setAttribute("aria-expanded", "false");
  }
});
$("#asset-search").addEventListener("input", (e) => {
  state.query = e.target.value;
  renderAssets();
});
$("#asset-sort").addEventListener("change", (e) => {
  state.sort = e.target.value;
  renderAssets();
});
$("#capability-search").addEventListener("input", (e) => {
  state.libraryQuery = e.target.value;
  renderLibrary();
});
$("#favorites-only").addEventListener("click", () => {
  state.libraryFavorites = !state.libraryFavorites;
  renderLibrary();
});
$("#modal-root").addEventListener("click", (e) => {
  if (e.target.classList.contains("modal-backdrop")) closeModal();
});
document.addEventListener("dblclick", (e) => {
  const card = e.target.closest("[data-asset]");
  if (card && !e.target.closest("button"))
    openPreview(assets.find((a) => a.id === card.dataset.asset));
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if ($("#modal-root").children.length) closeModal();
    else if (!$("#account-menu").hidden) {
      $("#account-menu").hidden = true;
      $("#account-button").setAttribute("aria-expanded", "false");
      $("#account-button").focus();
    } else showView("chat");
    return;
  }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    (state.view === "assets"
      ? $("#asset-search")
      : $("#capability-search")
    ).focus();
    return;
  }
  const modal = $(".modal");
  if (e.key === "Tab" && modal) {
    const items = [
      ...modal.querySelectorAll(
        "button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]",
      ),
    ].filter((el) => el.getClientRects().length);
    if (!items.length) return;
    const first = items[0],
      last = items.at(-1);
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
    return;
  }
  if (
    (e.key === "Enter" || e.key === " ") &&
    e.target.matches('[data-asset],[data-capability],[data-action="preview"]')
  ) {
    e.preventDefault();
    e.target.click();
  }
});
async function importFiles(files) {
  const targetProfile = state.profile;
  let added = 0;
  for (const file of files) {
    const ext = file.name.split(".").at(-1).toUpperCase();
    const kind =
      /^image\//.test(file.type) ||
      ["SVG", "PNG", "JPG", "JPEG", "WEBP", "GIF"].includes(ext)
        ? "image"
        : ["HTML", "HTM"].includes(ext)
          ? "web"
          : ["JSON", "CSV"].includes(ext)
            ? "data"
            : "document";
    const a = {
      id: "import-" + Date.now() + "-" + added,
      profile: targetProfile,
      name: file.name,
      kind,
      ext,
      bytes: file.size,
      time: "刚刚",
      order: 100 + Date.now(),
      tags: ["本页导入"],
      conversation: "手动导入",
      imported: true,
    };
    if (kind === "image") {
      if (file.size > 25 * 1024 * 1024) {
        notify("预览图片大小上限为 25 MB，请选择较小文件。");
        continue;
      }
      if (ext === "SVG") {
        a.text = await file.text();
        a.image = URL.createObjectURL(
          new Blob([a.text], { type: "image/svg+xml" }),
        );
      } else a.image = URL.createObjectURL(file);
      objectURLs.add(a.image);
    } else if (file.size < 2 * 1024 * 1024 && ext !== "PDF")
      a.text = await file.text();
    assets.push(a);
    if (state.profile === targetProfile) state.selected = a.id;
    added++;
  }
  state.filter = "all";
  state.query = "";
  $("#asset-search").value = "";
  renderAssets();
  if (added) notify(`已在${people.find(p => p.id === targetProfile).name}加入 ${added} 个文件预览，未上传或写入原文件。`);
}
$("#file-input").addEventListener("change", async (e) => {
  const files = [...e.target.files];
  e.target.value = "";
  await importFiles(files);
});
document.addEventListener("dragover", e => {
  if (!e.dataTransfer.types.includes("Files")) return;
  e.preventDefault();
  if (state.view === "assets") $(".asset-main").classList.add("drop-target");
});
document.addEventListener("dragleave", e => {
  if (!e.relatedTarget || !$(".asset-main").contains(e.relatedTarget)) $(".asset-main").classList.remove("drop-target");
});
document.addEventListener("drop", async e => {
  if (!e.dataTransfer.files.length) return;
  e.preventDefault();
  $(".asset-main").classList.remove("drop-target");
  if (state.view === "assets") await importFiles([...e.dataTransfer.files]);
});
window.addEventListener("beforeunload", () =>
  objectURLs.forEach((u) => URL.revokeObjectURL(u)),
);
hydrateIcons();
renderAccount();
const initial = location.hash.slice(1);
showView(
  ["assets", "skills", "agents", "chat"].includes(initial) ? initial : "assets",
);
