import { BrowserWindow, screen } from 'electron';
import type { Tab } from '../shared/model';

const SELECT = 'app://shell/tab-picker/select/';
const DISMISS = 'app://shell/tab-picker/dismiss';

function label(text: string): string {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}

function markup(tabs: Tab[], activeId: string): string {
  const rows = tabs
    .map((tab) => {
      const selected = tab.id === activeId;
      return `<a role="menuitemradio" aria-checked="${selected}" tabindex="${selected ? 0 : -1}"
        href="${SELECT}${encodeURIComponent(tab.id)}"><span>${label(tab.title || 'ChatGPT')}</span>${
          selected ? '<span class="current">✓ 当前</span>' : ''
        }</a>`;
    })
    .join('');
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-studio-tabs';">
    <title>全部标签</title><style>
    * { box-sizing: border-box }
    html, body { margin:0; height:100%; overflow:hidden }
    body { color:#171717; background:#fff; border:1px solid #dedede;
      font:14px -apple-system,"Segoe UI","Microsoft YaHei UI",sans-serif; display:flex; flex-direction:column }
    header { height:42px; flex:none; padding:0 13px; display:flex; justify-content:space-between; align-items:center;
      border-bottom:1px solid #eee }
    header span { color:#565656; font-size:12px }
    [role=menu] { min-height:0; overflow:auto; padding:6px 7px }
    a { height:36px; display:flex; align-items:center; justify-content:space-between; gap:8px;
      padding:0 8px; border:2px solid transparent; border-radius:6px; color:inherit; text-decoration:none }
    a > span:first-child { min-width:0; overflow:hidden; white-space:nowrap; text-overflow:ellipsis }
    a:hover { background:#f1f1f1 }
    a[aria-checked=true] { border-color:#252525; font-weight:600 }
    a:focus-visible { outline:1px dashed #555; outline-offset:-4px }
    .current { font-size:12px; flex:none }
    </style>
    <header><strong>全部标签</strong><span>${tabs.length} 个</span></header>
    <div role="menu" aria-label="全部标签">${rows}</div>
    <script nonce="studio-tabs">
      const items = Array.from(document.querySelectorAll('[role=menuitemradio]'));
      items.find(item => item.getAttribute('aria-checked') === 'true')?.focus();
      document.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
          event.preventDefault();
          location.href = '${DISMISS}';
        } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault();
          const previous = items.indexOf(document.activeElement);
          const next = (previous + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
          items[next]?.focus();
        }
      });
    </script></html>`;
}

export function showTabPicker(
  parent: BrowserWindow,
  anchor: { x: number; y: number },
  tabs: Tab[],
  activeId: string,
  select: (id: string) => void
): BrowserWindow {
  const content = parent.getContentBounds();
  const work = screen.getDisplayMatching(content).workArea;
  const width = 308;
  const height = Math.max(100, Math.min(404, 56 + tabs.length * 36, content.height - anchor.y - 8, work.height));
  const x = Math.max(
    work.x,
    Math.min(content.x + anchor.x, content.x + content.width - width - 8, work.x + work.width - width)
  );
  const y = Math.max(work.y, Math.min(content.y + anchor.y, work.y + work.height - height));
  const picker = new BrowserWindow({
    parent,
    width,
    height,
    x,
    y,
    show: false,
    frame: false,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    skipTaskbar: true,
    backgroundColor: '#ffffff',
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false }
  });
  if (process.platform === 'win32') picker.setMenuBarVisibility(false);
  const dismiss = () => {
    if (!picker.isDestroyed()) picker.destroy();
  };
  parent.on('resize', dismiss).on('move', dismiss).on('hide', dismiss).on('minimize', dismiss);
  picker.once('closed', () => {
    parent.off('resize', dismiss).off('move', dismiss).off('hide', dismiss).off('minimize', dismiss);
  });
  picker.on('blur', dismiss);
  picker.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  picker.webContents.on('will-navigate', (event) => {
    const { url } = event;
    event.preventDefault();
    if (url === DISMISS) {
      dismiss();
      parent.focus();
      parent.webContents.focus();
      return;
    }
    const tab = tabs.find((item) => SELECT + encodeURIComponent(item.id) === url);
    if (tab) {
      dismiss();
      select(tab.id);
    }
  });
  picker.once('ready-to-show', () => picker.show());
  void picker.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(markup(tabs, activeId))).catch(dismiss);
  return picker;
}
