import type { DownloadItem, Session, WebContents, WebFrameMain } from 'electron';
import type { AssetRecord, AssetSource } from '../shared/assets';
import { AssetStore } from './asset-store';

export type AssetDownloadOptions = {
  profileAvailable: () => boolean;
  source: (contents: WebContents, frame: WebFrameMain | null) => Omit<AssetSource, 'kind'>;
  changed?: (asset: AssetRecord) => void;
  failed?: (error: Error) => void;
};
type Job = { item: DownloadItem; finishing: boolean; lastUpdate: number; done: Promise<void>; resolve: () => void };
const bytes = (n: number) => Number.isSafeInteger(n) && n >= 0 ? n : 0;

/** One binding belongs to one immutable browser profile, even if the foreground account changes. */
export class AssetDownloads {
  private readonly jobs = new Map<string, Job>();
  private disposed = false;
  constructor(private readonly ses: Session, private readonly profileId: string, private readonly store: AssetStore, private readonly options: AssetDownloadOptions) {
    ses.on('will-download', this.onDownload);
  }
  private changed(record: AssetRecord): void {
    try { this.options.changed?.(record); }
    catch (error) { this.report(error); }
  }
  private report(error: unknown): void {
    try { this.options.failed?.(error instanceof Error ? error : new Error('资产下载归档失败')); }
    catch { /* A notification failure must not change the persisted transfer outcome. */ }
  }
  private readonly onDownload = (_event: Electron.Event, item: DownloadItem, contents: WebContents, frame: WebFrameMain | null) => {
    if (this.disposed || !this.options.profileAvailable()) { item.cancel(); return; }
    let reserved: AssetRecord | undefined;
    try {
      reserved = this.store.reserve(this.profileId, item.getFilename(), item.getMimeType(), { ...this.options.source(contents, frame), kind: 'download' }, bytes(item.getTotalBytes()));
      item.setSavePath(this.store.pendingPath(this.profileId, reserved.id));
    } catch (error) {
      try { if (reserved) this.changed(this.store.fail(this.profileId, reserved.id, 'failed', '无法创建下载文件，已取消下载。')); }
      catch (failure) { this.report(failure); }
      item.cancel(); this.report(error); return;
    }
    if (!reserved) return;
    const asset = reserved;
    let finish!: () => void;
    const job: Job = { item, finishing: false, lastUpdate: 0, done: new Promise<void>(resolve => { finish = resolve; }), resolve: () => finish() };
    this.jobs.set(asset.id, job);
    item.on('updated', (_event, state) => {
      if (job.finishing || this.disposed) return;
      const now = Date.now();
      const next = state === 'interrupted' ? 'interrupted' : item.isPaused() ? 'paused' : 'downloading';
      if (next === 'downloading' && now - job.lastUpdate < 800) return;
      job.lastUpdate = now;
      try { this.changed(this.store.progress(this.profileId, asset.id, bytes(item.getReceivedBytes()), bytes(item.getTotalBytes()), next)); }
      catch (error) { this.report(error); item.cancel(); }
    });
    item.once('done', (_event, state) => {
      job.finishing = true;
      void (async () => {
        try {
          if (state === 'completed' && this.options.profileAvailable() && !this.disposed) {
            this.changed(await this.store.complete(this.profileId, asset.id));
          } else {
            const cancelled = state === 'cancelled' || this.disposed || !this.options.profileAvailable();
            this.changed(this.store.fail(this.profileId, asset.id, cancelled ? 'cancelled' : 'interrupted', cancelled ? '下载已取消，未归档为可用资产。' : '下载中断，请从来源对话重新下载。'));
          }
        } catch (error) {
          try {
            const current = this.store.get(this.profileId, asset.id);
            if (current.status !== 'cancelled') {
              this.changed(this.store.fail(this.profileId, asset.id, 'failed', error instanceof Error ? error.message : '文件归档失败，请重试。'));
              this.report(error);
            }
          } catch (failure) { this.report(failure); }
        } finally { this.jobs.delete(asset.id); job.resolve(); }
      })();
    });
    this.changed(asset);
  };
  activeIds(): string[] { return [...this.jobs.keys()]; }
  pause(assetId: string): void {
    this.store.get(this.profileId, assetId);
    const job = this.jobs.get(assetId);
    if (!job || job.finishing) throw new Error('该下载已结束，无法暂停');
    job.item.pause();
    this.changed(this.store.progress(this.profileId, assetId, bytes(job.item.getReceivedBytes()), bytes(job.item.getTotalBytes()), 'paused'));
  }
  resume(assetId: string): void {
    const asset = this.store.get(this.profileId, assetId);
    const job = this.jobs.get(assetId);
    if (!job || job.finishing || (!job.item.isPaused() && !job.item.canResume())) throw new Error('该下载无法继续，请从来源对话重新下载');
    if (!['downloading','paused','interrupted'].includes(asset.status)) throw new Error('下载状态已经改变');
    job.item.resume();
    this.changed(this.store.progress(this.profileId, assetId, bytes(job.item.getReceivedBytes()), bytes(job.item.getTotalBytes()), 'downloading'));
  }
  cancel(assetId: string): void {
    this.store.get(this.profileId, assetId);
    const job = this.jobs.get(assetId);
    if (!job) throw new Error('该下载已结束');
    if (job.finishing) this.changed(this.store.fail(this.profileId, assetId, 'cancelled', '文件处理已取消，未归档为可用资产。'));
    else job.item.cancel();
  }
  async whenIdle(): Promise<void> { await Promise.all([...this.jobs.values()].map(job => job.done)); }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true; this.ses.removeListener('will-download', this.onDownload);
    for (const [id, job] of this.jobs) {
      if (job.finishing) this.changed(this.store.fail(this.profileId, id, 'cancelled', '账号空间已关闭，文件处理已取消。'));
      else job.item.cancel();
    }
  }
}
