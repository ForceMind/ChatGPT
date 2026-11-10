import type { UpdateStatus as UpdateState } from '../shared/updates';
import { Icon } from './icons';
import './update-status.css';

function downloadProgress(status?: UpdateState): number | undefined {
  const value = status?.progress;
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : undefined;
}

function updateLabel(status?: UpdateState): string {
  switch (status?.phase) {
    case 'checking':
      return '正在检查更新';
    case 'available':
      return '发现可用更新';
    case 'downloading':
      if (status.stage === 'connect') return '正在连接下载源';
      if (status.stage === 'prepare') return '正在准备安装';
      if (status.stage === 'verify') return '正在校验更新';
      if ((downloadProgress(status) ?? 0) >= 100) return '校验/准备安装';
      return '正在下载更新';
    case 'ready':
      return status.installMode === 'manual' ? '更新安装包可保存' : '更新已准备';
    case 'error':
      return '更新暂未完成';
    default:
      return status?.message || '可手动检查更新';
  }
}

function DownloadProgress({ status, compact = false }: { status: UpdateState; compact?: boolean }) {
  if (status.phase !== 'downloading' && (status.phase !== 'error' || downloadProgress(status) === undefined))
    return null;
  const value = downloadProgress(status);
  const total = status.totalBytes;
  const received = status.transferredBytes;
  const bytes =
    Number.isSafeInteger(total) &&
    total! > 0 &&
    Number.isSafeInteger(received) &&
    received! >= 0 &&
    received! <= total!;
  return (
    <div>
      <div className="update-download">
        <progress className="update-progress" aria-label="更新下载进度" max={100} value={value} />
        <span className="update-percent">{value === undefined ? '等待进度' : `${Math.floor(value)}%`}</span>
      </div>
      {!compact && bytes && (
        <p className="update-bytes">
          {(received! / 1_000_000).toFixed(1)} / {(total! / 1_000_000).toFixed(1)} MB
        </p>
      )}
    </div>
  );
}

export function UpdateStatusButton({
  status,
  expanded,
  disabled = false,
  onClick
}: {
  status?: UpdateState;
  expanded: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  if (!status || status.phase === 'idle') return null;
  const label = updateLabel(status);
  return (
    <button
      type="button"
      className="update-status-button"
      aria-label="查看应用更新"
      aria-haspopup="menu"
      aria-expanded={expanded}
      disabled={disabled}
      data-update-phase={status.phase}
      data-update-stage={status.stage}
      title={[label, status.version, status.message].filter(Boolean).join(' · ')}
      onClick={onClick}
    >
      <span className="update-status-button-label">{label}</span>
      <DownloadProgress status={status} compact />
    </button>
  );
}

export function UpdateStatusCard({
  status,
  currentVersion,
  onCheck
}: {
  status?: UpdateState;
  currentVersion: string;
  onCheck: () => void | Promise<void>;
}) {
  const phase = status?.phase || 'idle';
  const label = updateLabel(status);
  const ready = phase === 'ready';
  const manual = status?.installMode === 'manual';
  const pending = phase === 'checking' || phase === 'downloading';
  const stages = ['检查更新', '下载文件', '校验文件', '准备安装'];
  const active =
    phase === 'checking' || status?.stage === 'check'
      ? 0
      : phase === 'ready' || status?.stage === 'prepare' || status?.stage === 'install'
        ? 3
        : status?.stage === 'verify'
          ? 2
          : 1;
  return (
    <div
      className="update-status-card"
      role="group"
      aria-label="应用更新"
      data-update-phase={phase}
      data-update-stage={status?.stage}
    >
      <div className="update-status-card-heading">
        <strong>应用更新</strong>
        <span>当前版本 {currentVersion}</span>
      </div>
      <p className="update-status-phase" role="status">
        {label}
      </p>
      {phase !== 'idle' && phase !== 'available' && (
        <ol className="update-stages" aria-label="更新步骤">
          {stages.map((stage, index) => (
            <li
              key={stage}
              aria-current={active === index ? 'step' : undefined}
              data-complete={index < active || phase === 'ready'}
            >
              {stage}
            </li>
          ))}
        </ol>
      )}
      {status?.version && status.version !== currentVersion && (
        <p className="update-status-version">
          {phase === 'idle' ? '官网公开版本' : '目标版本'} {status.version}
        </p>
      )}
      {status && <DownloadProgress status={status} />}
      {status?.message && status.message !== label && <p className="update-status-message">{status.message}</p>}
      <button
        type="button"
        className={'menu-action update-status-action' + (ready ? ' is-ready' : '')}
        role="menuitem"
        disabled={pending}
        onClick={() => void onCheck()}
      >
        <Icon name={ready ? 'arrowDown' : 'refresh'} size={15} />
        <span className="update-status-action-copy">
          {ready && (
            <span className="update-status-action-state">
              {manual ? `ZIP ${status?.version || ''} 可保存` : `更新 ${status?.version || ''} 已准备`}
            </span>
          )}
          <span>
            {ready
              ? manual
                ? '保存更新安装包'
                : '立即安装并重启'
              : phase === 'available'
                ? '下载更新'
                : phase === 'error'
                  ? status?.stage === 'check'
                    ? '重试检查'
                    : '重试更新'
                  : '检查更新'}
          </span>
        </span>
      </button>
    </div>
  );
}
