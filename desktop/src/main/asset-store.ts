import { createHash, randomUUID } from 'node:crypto';
import { constants, closeSync, existsSync, fsyncSync, lstatSync, mkdirSync, openSync, readFileSync, readdirSync, realpathSync, renameSync, unlinkSync, writeFileSync, createWriteStream } from 'node:fs';
import { open } from 'node:fs/promises';
import path from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { AssetKind, AssetLibrary, AssetRecord, AssetSource, AssetStatus } from '../shared/assets';
import { isChatURL } from './policy';

const MAX_INDEX_BYTES = 32 * 1024 * 1024;
const MAX_ASSETS = 20_000;
const statuses = new Set<AssetStatus>(['downloading', 'paused', 'processing', 'ready', 'interrupted', 'cancelled', 'failed', 'missing', 'changed']);
const kinds = new Set<AssetKind>(['image', 'web', 'document', 'data', 'audio', 'video', 'other']);
const activeStatuses = new Set<AssetStatus>(['downloading', 'paused', 'processing']);
const idPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
const isId = (v: unknown): v is string => typeof v === 'string' && idPattern.test(v);
const number = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;
const label = (v: unknown, max: number): v is string => typeof v === 'string' && v.length > 0 && v.length <= max && !/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/.test(v);
const fail = (): never => { throw new Error('资产索引格式无效，已停止覆盖现有数据。'); };
function requireId(value: string): void { if (!isId(value)) throw new Error('无效的资产或账号标识'); }
function mimeType(value: string): string { const clean = value.split(';', 1)[0].trim().toLowerCase(); return clean.length <= 150 && /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(clean) ? clean : 'application/octet-stream'; }

export function safeAssetFilename(value: string): string {
  let result = path.posix.basename(value.replace(/\\/g, '/')).normalize('NFC').replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g, '_').trim().replace(/[. ]+$/g, '');
  if (!result || result === '.' || result === '..') result = '未命名文件';
  const extension = path.extname(result);
  const suffix = Buffer.byteLength(extension) <= 24 ? extension : '';
  let body = ''; let bytes = Buffer.byteLength(suffix);
  for (const char of suffix ? result.slice(0, -suffix.length) : result) {
    const length = Buffer.byteLength(char);
    if (bytes + length > 180) break;
    body += char; bytes += length;
  }
  return (body || 'file') + suffix;
}
function safeStoredFilename(value: unknown): value is string { return typeof value === 'string' && value.length > 0 && value === safeAssetFilename(value) && !/[\\/]/.test(value) && !value.startsWith('.'); }
export function assetKind(filename: string, mime: string): AssetKind {
  const ext = path.extname(filename).toLowerCase();
  if (mime.startsWith('image/') || ['.png','.jpg','.jpeg','.webp','.gif','.svg','.avif','.heic','.bmp','.tif','.tiff'].includes(ext)) return 'image';
  if (mime === 'text/html' || ['.html','.htm'].includes(ext)) return 'web';
  if (mime.startsWith('audio/')) return 'audio';
  if (mime.startsWith('video/')) return 'video';
  if (['.json','.csv','.tsv','.yaml','.yml','.js','.ts','.tsx','.jsx','.css','.sql','.py','.log','.xml'].includes(ext)) return 'data';
  if (mime.startsWith('text/') || ['.pdf','.md','.txt','.doc','.docx','.xls','.xlsx','.ppt','.pptx','.rtf'].includes(ext)) return 'document';
  return 'other';
}
function sourceURL(value?: string): string | undefined {
  if (!value || !isChatURL(value)) return undefined;
  const url = new URL(value);
  if (/^\/(auth|api)(\/|$)/.test(url.pathname)) return undefined;
  url.search = ''; url.hash = '';
  return url.href;
}
function normalizeSource(value: AssetSource): AssetSource {
  if (!value || !['download','import','conversion'].includes(value.kind)) return fail();
  if (value.tabId !== undefined && !isId(value.tabId)) return fail();
  if (value.fromAssetId !== undefined && !isId(value.fromAssetId)) return fail();
  if ((value.kind === 'conversion') !== !!value.fromAssetId) return fail();
  for (const key of ['profileName','title','conversationURL','conversionFormat'] as const) if (value[key] !== undefined && typeof value[key] !== 'string') return fail();
  const clean = (v: string | undefined, max: number) => v?.replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g, ' ').trim().slice(0, max) || undefined;
  return {
    kind: value.kind,
    profileName: clean(value.profileName, 40),
    tabId: value.tabId,
    conversationURL: sourceURL(value.conversationURL),
    title: clean(value.title, 240),
    fromAssetId: value.fromAssetId,
    conversionFormat: clean(value.conversionFormat, 20)
  };
}
export function validateAssetLibrary(value: unknown): AssetLibrary {
  if (!object(value) || value.schema !== 1 || !Array.isArray(value.assets) || value.assets.length > MAX_ASSETS) return fail();
  const ids = new Map<string, AssetRecord>();
  const records = value.assets.map((raw: unknown): AssetRecord => {
    if (!object(raw) || !isId(raw.id) || !isId(raw.profileId) || ids.has(raw.id) || !label(raw.name, 240) || /[\\/]/.test(raw.name) || !safeStoredFilename(raw.fileName) || !label(raw.mime, 160) || mimeType(raw.mime) !== raw.mime || !kinds.has(raw.kind as AssetKind) || !statuses.has(raw.status as AssetStatus)) return fail();
    if (!number(raw.size) || !number(raw.receivedBytes) || !number(raw.totalBytes) || !number(raw.createdAt) || !number(raw.updatedAt) || !Array.isArray(raw.tags) || raw.tags.length > 12 || raw.tags.some(t => !label(t, 32)) || new Set(raw.tags).size !== raw.tags.length || typeof raw.favorite !== 'boolean' || !object(raw.source)) return fail();
    if (raw.trashedAt !== undefined && !number(raw.trashedAt)) return fail();
    if (raw.error !== undefined && !label(raw.error, 500)) return fail();
    if (raw.sha256 !== undefined && (typeof raw.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(raw.sha256))) return fail();
    if (raw.status === 'ready' && (!raw.sha256 || raw.receivedBytes !== raw.size || raw.totalBytes !== raw.size)) return fail();
    const normalized = normalizeSource(raw.source as AssetSource);
    for (const key of ['profileName','title','tabId','conversationURL','fromAssetId','conversionFormat'] as const) if (raw.source[key] !== undefined && raw.source[key] !== normalized[key]) return fail();
    if (normalized.kind === 'conversion' && !normalized.fromAssetId) return fail();
    if (normalized.kind !== 'conversion' && normalized.fromAssetId) return fail();
    const record: AssetRecord = { id: raw.id, profileId: raw.profileId, name: raw.name, fileName: raw.fileName, mime: raw.mime, kind: raw.kind as AssetKind, status: raw.status as AssetStatus, size: raw.size, receivedBytes: raw.receivedBytes, totalBytes: raw.totalBytes, createdAt: raw.createdAt, updatedAt: raw.updatedAt, tags: raw.tags as string[], favorite: raw.favorite, source: normalized };
    if (raw.sha256 !== undefined) record.sha256 = raw.sha256 as string;
    if (raw.trashedAt !== undefined) record.trashedAt = raw.trashedAt as number;
    if (raw.error !== undefined) record.error = raw.error as string;
    ids.set(record.id, record); return record;
  });
  const done = new Set<string>();
  for (const item of records) {
    let current: AssetRecord | undefined = item;
    const chain = new Set<string>();
    while (current && !done.has(current.id)) {
      if (chain.has(current.id)) return fail();
      chain.add(current.id);
      const parentId: string | undefined = current.source.fromAssetId;
      if (!parentId) break;
      const parent = ids.get(parentId);
      if (!parent || parent.profileId !== current.profileId) return fail();
      current = parent;
    }
    for (const id of chain) done.add(id);
  }
  return structuredClone({ schema: 1, assets: records });
}
function atomicJSON(file: string, value: AssetLibrary): void {
  const temporary = `${file}.${randomUUID()}.tmp`;
  let fd: number | undefined;
  try {
    fd = openSync(temporary, 'wx', 0o600);
    writeFileSync(fd, JSON.stringify(value) + '\n'); fsyncSync(fd); closeSync(fd); fd = undefined;
    renameSync(temporary, file);
  } finally {
    if (fd !== undefined) closeSync(fd);
    if (existsSync(temporary)) unlinkSync(temporary);
  }
}

export class AssetStore {
  readonly directory: string;
  readonly filesDirectory: string;
  readonly indexFile: string;
  readonly backupFile: string;
  readonly recoveryNotice?: string;
  private current: AssetLibrary;
  constructor(dataDirectory: string) {
    if (!path.isAbsolute(dataDirectory)) throw new Error('资产数据目录必须为绝对路径');
    this.directory = path.join(dataDirectory, 'assets');
    mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    if (lstatSync(this.directory).isSymbolicLink()) throw new Error('资产目录不能是符号链接');
    this.filesDirectory = path.join(realpathSync(this.directory), 'files');
    mkdirSync(this.filesDirectory, { recursive: true, mode: 0o700 });
    if (lstatSync(this.filesDirectory).isSymbolicLink()) throw new Error('资产文件目录不能是符号链接');
    this.indexFile = path.join(this.directory, 'library.json');
    this.backupFile = path.join(this.directory, 'library.backup.json');
    if (!existsSync(this.indexFile) && !existsSync(this.backupFile)) {
      if (readdirSync(this.filesDirectory).some(n => !n.startsWith('._') && n !== '.DS_Store')) throw new Error('发现资产文件但索引和备份均缺失，请保留目录并恢复索引。');
      this.current = { schema: 1, assets: [] };
      atomicJSON(this.indexFile, this.current);
    } else {
      try { this.current = this.readIndex(this.indexFile); }
      catch {
        try { this.current = this.readIndex(this.backupFile); }
        catch { throw new Error('资产索引和备份均无法读取，原文件已保留，程序不会清空或重建资产库。'); }
        if (existsSync(this.indexFile)) renameSync(this.indexFile, path.join(this.directory, `library.corrupt-${Date.now()}-${randomUUID()}.json`));
        atomicJSON(this.indexFile, this.current);
        this.recoveryNotice = '已从有效备份恢复资产索引，损坏索引和全部文件均已保留。';
      }
    }
    if (this.current.assets.some(a => activeStatuses.has(a.status))) {
      const next = this.read();
      for (const asset of next.assets) if (activeStatuses.has(asset.status)) {
        asset.status = 'interrupted'; asset.error = '应用退出时任务尚未完成，请从来源重新下载或导入。'; asset.updatedAt = Date.now();
      }
      this.save(next);
    }
  }
  private readIndex(file: string): AssetLibrary {
    const stat = lstatSync(file);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_INDEX_BYTES) return fail();
    return validateAssetLibrary(JSON.parse(readFileSync(file, 'utf8')));
  }
  read(): AssetLibrary { return structuredClone(this.current); }
  list(profileId: string): AssetRecord[] { requireId(profileId); return this.read().assets.filter(a => a.profileId === profileId); }
  get(profileId: string, assetId: string): AssetRecord {
    requireId(profileId); requireId(assetId);
    const asset = this.current.assets.find(a => a.id === assetId && a.profileId === profileId);
    if (!asset) throw new Error('该账号中不存在这份资产');
    return structuredClone(asset);
  }
  private save(next: AssetLibrary): void {
    if (lstatSync(this.directory).isSymbolicLink() || realpathSync(this.directory) !== path.dirname(this.filesDirectory)) throw new Error('资产索引目录已改变，请先恢复目录。');
    const valid = validateAssetLibrary(next);
    if (Buffer.byteLength(JSON.stringify(valid)) > MAX_INDEX_BYTES) throw new Error('资产索引已达到容量上限，请先导出整理。');
    atomicJSON(this.backupFile, this.current); atomicJSON(this.indexFile, valid); this.current = valid;
  }
  private patch(profileId: string, assetId: string, change: (asset: AssetRecord) => void): AssetRecord {
    this.get(profileId, assetId);
    const next = this.read(); const asset = next.assets.find(a => a.id === assetId && a.profileId === profileId)!;
    change(asset); asset.updatedAt = Date.now(); this.save(next); return this.get(profileId, assetId);
  }
  private folder(asset: AssetRecord, create = false): string {
    let folder = this.filesDirectory;
    if (lstatSync(folder).isSymbolicLink() || realpathSync(folder) !== this.filesDirectory) throw new Error('资产文件目录已改变，请先恢复目录。');
    for (const id of [asset.profileId, asset.id]) {
      requireId(id); folder = path.join(folder, id);
      if (create && !existsSync(folder)) mkdirSync(folder, { mode: 0o700 });
      const stat = lstatSync(folder);
      if (!stat.isDirectory() || stat.isSymbolicLink() || realpathSync(folder) !== folder) throw new Error('资产路径包含不安全的目录链接');
    }
    return folder;
  }
  private filePath(asset: AssetRecord, pending = false): string {
    if (!safeStoredFilename(asset.fileName)) return fail();
    const result = path.join(this.folder(asset), pending ? `${asset.fileName}.part` : asset.fileName);
    if (existsSync(result) && lstatSync(result).isSymbolicLink()) throw new Error('资产文件不能是符号链接');
    return result;
  }
  reserve(profileId: string, filename: string, mime: string, source: AssetSource, totalBytes = 0): AssetRecord {
    requireId(profileId);
    if (!number(totalBytes)) throw new Error('文件大小无效');
    if (this.current.assets.length >= MAX_ASSETS) throw new Error('资产数量已达到上限，请先导出整理。');
    const cleanSource = normalizeSource(source);
    if (cleanSource.kind === 'conversion') {
      const parent = this.get(profileId, cleanSource.fromAssetId || '');
      if (parent.status !== 'ready' || parent.trashedAt !== undefined) throw new Error('请先恢复并检查原始文件');
    }
    let fileName = safeAssetFilename(filename);
    if (fileName.startsWith('.')) fileName = safeAssetFilename('file-' + fileName.replace(/^\.+/, ''));
    const now = Date.now();
    const asset: AssetRecord = { id: randomUUID(), profileId, name: fileName, fileName, mime: mimeType(mime), kind: assetKind(fileName, mimeType(mime)), status: cleanSource.kind === 'download' ? 'downloading' : 'processing', size: 0, receivedBytes: 0, totalBytes, createdAt: now, updatedAt: now, tags: [], favorite: false, source: cleanSource };
    this.folder(asset, true);
    const next = this.read(); next.assets.push(asset); this.save(next); return this.get(profileId, asset.id);
  }
  pendingPath(profileId: string, assetId: string): string {
    const asset = this.get(profileId, assetId);
    if (!activeStatuses.has(asset.status) || asset.trashedAt !== undefined) throw new Error('资产当前不接受写入');
    return this.filePath(asset, true);
  }
  progress(profileId: string, assetId: string, receivedBytes: number, totalBytes: number, status: 'downloading' | 'paused' | 'interrupted'): AssetRecord {
    const asset = this.get(profileId, assetId);
    if (!['downloading','paused','interrupted'].includes(asset.status)) return asset;
    if (!number(receivedBytes) || !number(totalBytes)) throw new Error('下载进度无效');
    return this.patch(profileId, assetId, a => { a.receivedBytes = receivedBytes; a.totalBytes = totalBytes; a.status = status; });
  }
  fail(profileId: string, assetId: string, status: 'failed' | 'cancelled' | 'interrupted', message: string): AssetRecord {
    const asset = this.get(profileId, assetId);
    if (asset.status === 'ready') return asset;
    return this.patch(profileId, assetId, a => { a.status = status; a.error = message.replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g, ' ').slice(0, 500) || '文件操作失败，请重试。'; });
  }
  private async digest(file: string): Promise<{ size: number; sha256: string }> {
    const handle = await open(file, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
    try {
      const before = await handle.stat();
      if (!before.isFile()) throw new Error('只支持普通资产文件');
      const hash = createHash('sha256'); let bytes = 0;
      for await (const chunk of handle.createReadStream({ autoClose: false })) { hash.update(chunk); bytes += chunk.length; }
      const after = await handle.stat();
      if (bytes !== before.size || before.size !== after.size || before.mtimeMs !== after.mtimeMs) throw new Error('文件在校验时发生变化，请重新导入');
      return { size: bytes, sha256: hash.digest('hex') };
    } finally { await handle.close(); }
  }
  async complete(profileId: string, assetId: string): Promise<AssetRecord> {
    const asset = this.get(profileId, assetId);
    if (!activeStatuses.has(asset.status) && asset.status !== 'interrupted') throw new Error('资产任务已结束，不能重复完成');
    this.patch(profileId, assetId, a => { a.status = 'processing'; });
    const pending = this.filePath(asset, true);
    try {
      const digest = await this.digest(pending);
      const latest = this.get(profileId, assetId);
      if (latest.status !== 'processing' || latest.trashedAt !== undefined) throw new Error('资产任务已取消');
      const destination = this.filePath(latest);
      if (existsSync(destination)) throw new Error('目标资产文件已存在，已停止覆盖');
      const fd = openSync(pending, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
      try { fsyncSync(fd); } finally { closeSync(fd); }
      renameSync(pending, destination);
      return this.patch(profileId, assetId, a => { a.status = 'ready'; a.size = digest.size; a.receivedBytes = digest.size; a.totalBytes = digest.size; a.sha256 = digest.sha256; delete a.error; });
    } catch (error) {
      if (this.get(profileId, assetId).status === 'processing') this.fail(profileId, assetId, 'failed', error instanceof Error ? error.message : '文件校验或归档失败');
      throw error;
    }
  }
  async importFile(profileId: string, sourceFile: string, source: AssetSource = { kind: 'import' }, name?: string, mime = 'application/octet-stream'): Promise<AssetRecord> {
    if (!path.isAbsolute(sourceFile)) throw new Error('导入文件必须来自原生文件选择结果');
    if (source.kind === 'download') throw new Error('下载文件应从下载事件归档');
    const handle = await open(sourceFile, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
    let asset: AssetRecord | undefined;
    try {
      const before = await handle.stat();
      if (!before.isFile()) throw new Error('不支持导入目录或设备文件');
      asset = this.reserve(profileId, name || path.basename(sourceFile), mime, source, before.size);
      const destination = this.pendingPath(profileId, asset.id);
      let copied = 0;
      const counter = new Transform({ transform(chunk: Buffer, _encoding, callback) { copied += chunk.length; callback(null, chunk); } });
      await pipeline(handle.createReadStream({ autoClose: false }), counter, createWriteStream(destination, { flags: 'wx', mode: 0o600 }));
      const after = await handle.stat();
      if (copied !== before.size || before.size !== after.size || before.mtimeMs !== after.mtimeMs) throw new Error('导入过程中文件发生变化，原文件未修改，请重试');
      return await this.complete(profileId, asset.id);
    } catch (error) {
      if (asset && activeStatuses.has(this.get(profileId, asset.id).status)) this.fail(profileId, asset.id, 'failed', error instanceof Error ? error.message : '导入失败，原文件已保留');
      throw error;
    } finally { await handle.close(); }
  }
  resolveFile(profileId: string, assetId: string): string {
    const asset = this.get(profileId, assetId);
    if (asset.status !== 'ready' || asset.trashedAt !== undefined) throw new Error('资产尚未就绪，或已移入回收站');
    const result = this.filePath(asset);
    const stat = lstatSync(result);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('资产文件已丢失或被替换，请重新导入');
    return result;
  }
  async verifyFile(profileId: string, assetId: string): Promise<string> {
    const asset = this.get(profileId, assetId);
    let file: string;
    try { file = this.resolveFile(profileId, assetId); }
    catch (error) {
      if (asset.status === 'ready' && asset.trashedAt === undefined) this.patch(profileId, assetId, a => { a.status = 'missing'; a.error = '资产文件已丢失或被替换，请重新导入。'; });
      throw error;
    }
    let result: { size: number; sha256: string };
    try { result = await this.digest(file); }
    catch (error) {
      const latest = this.get(profileId, assetId);
      if (latest.status === 'ready' && latest.trashedAt === undefined) this.patch(profileId, assetId, a => { a.status = 'changed'; a.error = '文件校验失败或正在变化，请重新导入后使用。'; });
      throw error;
    }
    if (result.size !== asset.size || result.sha256 !== asset.sha256) {
      this.patch(profileId, assetId, a => { a.status = 'changed'; a.error = '资产文件已在应用外发生变化，请重新导入后使用。'; });
      throw new Error('文件摘要与归档记录不一致，已停止读取或发送');
    }
    const latest = this.get(profileId, assetId);
    if (latest.status !== 'ready' || latest.trashedAt !== undefined) throw new Error('资产状态已改变，请重新选择');
    return this.resolveFile(profileId, assetId);
  }
  rename(profileId: string, assetId: string, name: string): AssetRecord {
    const asset = this.get(profileId, assetId);
    const clean = name.trim();
    if (!label(clean, 240) || /[\\/]/.test(clean) || clean === '.' || clean === '..') throw new Error('文件名不能为空，也不能包含路径或控制字符');
    if (path.extname(clean).toLowerCase() !== path.extname(asset.fileName).toLowerCase()) throw new Error('请保留文件扩展名，使用“转换格式”创建其他格式');
    return this.patch(profileId, assetId, a => { a.name = clean; });
  }
  setTags(profileId: string, assetId: string, tags: string[]): AssetRecord {
    if (!Array.isArray(tags) || tags.length > 12 || tags.some(t => !label(t, 32))) throw new Error('最多支持 12 个标签，每个标签最多 32 个字符');
    return this.patch(profileId, assetId, a => { a.tags = [...new Set(tags.map(t => t.trim()).filter(Boolean))]; });
  }
  setFavorite(profileId: string, assetId: string, favorite: boolean): AssetRecord {
    if (typeof favorite !== 'boolean') throw new Error('收藏状态无效');
    return this.patch(profileId, assetId, a => { a.favorite = favorite; });
  }
  setTrashed(profileId: string, assetId: string, trashed: boolean): AssetRecord {
    if (typeof trashed !== 'boolean') throw new Error('回收站状态无效');
    const asset = this.get(profileId, assetId);
    if (trashed && activeStatuses.has(asset.status)) throw new Error('请先取消正在进行的下载或转换');
    return this.patch(profileId, assetId, a => { if (trashed) a.trashedAt = Date.now(); else delete a.trashedAt; });
  }
}
