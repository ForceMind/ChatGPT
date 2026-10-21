const icons: Record<string,string> = {
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
export const wbIcon = (name: string) =>
  `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.document}</svg>`;
