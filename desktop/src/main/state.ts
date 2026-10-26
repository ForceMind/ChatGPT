import {validWorkspace} from '../shared/recovery';
import { DEFAULT_LANGUAGE,validBrowserLanguage,validTimeZone } from '../shared/profile-preferences';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync, openSync, fsyncSync, closeSync } from 'node:fs';
import path from 'node:path';
import { COLORS, type Profile, type SavedState, type Tab } from '../shared/model';
import { homeURL, isAllowedURL } from './policy';

export const MAX_PROFILES = 32;
export const MAX_TABS = 160;
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
export function validLabel(v: unknown, max: number, required = false): v is string { return typeof v === 'string' && v.length <= max && !/[\u0000-\u001f\u007f]/.test(v) && (!required || v.trim().length > 0); }
export function makeTab(profileId: string, url: string): Tab { return { id: randomUUID(), profileId, url, title: 'ChatGPT', pinned: false }; }
export function initialState(fixtureOrigin?: string): SavedState {
  const id = randomUUID();
  const tab = makeTab(id, homeURL(fixtureOrigin));
  return { schema: 1, profiles: [{ id, name: '个人账号', email: '', color: COLORS[0], activeTabId: tab.id, createdAt: Date.now(),language:DEFAULT_LANGUAGE,timeZone:'system' }], tabs: [tab], activeProfileId: id, sidebarOpen: true, closedTabs: [], bounds: { width: 1360, height: 900 } };
}
export function validateState(raw: unknown, fixtureOrigin?: string): SavedState {
  const fail = (): never => { throw new Error('账号状态文件格式无效，已停止覆盖现有数据。'); };
  if (!object(raw) || raw.schema !== 1 || !Array.isArray(raw.profiles) || !Array.isArray(raw.tabs) || !Array.isArray(raw.closedTabs) || typeof raw.sidebarOpen !== 'boolean' || !object(raw.bounds)) return fail();
  if (raw.profiles.length < 1 || raw.profiles.length > MAX_PROFILES || raw.tabs.length > MAX_TABS || raw.closedTabs.length > 24) return fail();
  // Retain the 448 legacy favorites while allowing 32 new roles and one new Skill.
  if (raw.capabilityFavorites !== undefined && (!Array.isArray(raw.capabilityFavorites) || raw.capabilityFavorites.length > 481 || new Set(raw.capabilityFavorites).size !== raw.capabilityFavorites.length || !raw.capabilityFavorites.every(id => typeof id === 'string' && /^(skill|agent|studio-agent)-\d{3}$/.test(id)))) return fail();
  const profiles = raw.profiles as Profile[], tabs = raw.tabs as Tab[], closedTabs = raw.closedTabs as Tab[];
  const ids = new Set<string>();
  for (const p of profiles) {
    if (!object(p) || !uuid(p.id) || ids.has(p.id) || !validLabel(p.name, 40, true) || !validLabel(p.email, 120) || !(COLORS as readonly string[]).includes(p.color) || !uuid(p.activeTabId) || !Number.isFinite(p.createdAt)) return fail();
    if((p.language!==undefined&&!validBrowserLanguage(p.language))||(p.timeZone!==undefined&&!validTimeZone(p.timeZone)))return fail();
    if(p.recoveryEpoch!==undefined&&(!Number.isSafeInteger(p.recoveryEpoch)||p.recoveryEpoch<0))return fail();
    if(p.workspace!==undefined&&!validWorkspace(p.workspace))return fail();
    ids.add(p.id);
  }
  if (!ids.has(raw.activeProfileId as string)) return fail();
  const tabIds = new Set<string>();
  for (const t of [...tabs, ...closedTabs]) {
    if (!object(t) || !uuid(t.id) || tabIds.has(t.id) || !ids.has(t.profileId) || !validLabel(t.title, 240) || !isAllowedURL(t.url, fixtureOrigin) || typeof t.pinned !== 'boolean') return fail();
    if(t.loaded!==undefined&&typeof t.loaded!=='boolean'||t.closedPosition!==undefined&&(!Number.isSafeInteger(t.closedPosition)||t.closedPosition<0||t.closedPosition>MAX_TABS))return fail();
    tabIds.add(t.id);
  }
  for (const p of profiles) if (!tabs.some(t => t.id === p.activeTabId && t.profileId === p.id)) return fail();
  if(raw.windowMode!==undefined&&(!object(raw.windowMode)||typeof raw.windowMode.maximized!=='boolean'||typeof raw.windowMode.fullscreen!=='boolean'))return fail();
  const b = raw.bounds;
  if (typeof b.width !== 'number' || typeof b.height !== 'number' || !Number.isFinite(b.width) || !Number.isFinite(b.height) || b.width < 700 || b.height < 480 || b.width > 10000 || b.height > 10000) return fail();
  for (const key of ['x', 'y']) if (b[key] !== undefined && (typeof b[key] !== 'number' || !Number.isFinite(b[key]))) return fail();
  return structuredClone(raw) as SavedState;
}
function atomicWrite(file: string, value: SavedState | string): void {
  const tmp = `${file}.${randomUUID()}.tmp`;
  const fd = openSync(tmp, 'wx', 0o600);
  try { writeFileSync(fd, typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`); fsyncSync(fd); } finally { closeSync(fd); }
  renameSync(tmp, file);
}
export class StateStore {
  private current: SavedState;
  readonly file: string;
  readonly backup: string;
  readonly recoveryNotice?: string;
  constructor(dir: string, private fixtureOrigin?: string) {
    mkdirSync(dir, { recursive: true, mode: 0o700 });
    this.file = path.join(dir, 'state.json'); this.backup = path.join(dir, 'state.backup.json');
    if (!existsSync(this.file) && !existsSync(this.backup)) {
      this.current = initialState(fixtureOrigin); atomicWrite(this.file, this.current); return;
    }
    try { this.current = this.readFile(this.file); }
    catch {
      try { this.current = this.readFile(this.backup); }
      catch { throw new Error('账号状态和备份均无法读取。请保留数据目录并恢复有效备份，程序不会自动重建或清空账号。'); }
      if (existsSync(this.file)) writeFileSync(path.join(dir, `state.corrupt-${Date.now()}.json`), readFileSync(this.file), { mode: 0o600 });
      atomicWrite(this.file, this.current);
      this.recoveryNotice = '已从有效备份恢复账号和标签，原状态文件已保留。';
    }
  }
  private readFile(file: string): SavedState {
    const text = readFileSync(file, 'utf8');
    if (Buffer.byteLength(text) > 2_000_000) throw new Error('状态文件过大');
    return validateState(JSON.parse(text), this.fixtureOrigin);
  }
  read(): SavedState { return structuredClone(this.current); }
  commit(mutate: (state: SavedState) => void): SavedState {
    const next = this.read(); mutate(next);
    const valid = validateState(next, this.fixtureOrigin);
    atomicWrite(this.backup, this.current);
    atomicWrite(this.file, valid);
    this.current = valid;
    return this.read();
  }
}
