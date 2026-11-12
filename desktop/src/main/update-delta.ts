import { randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { open, unlink, type FileHandle } from 'node:fs/promises';
import path from 'node:path';
import { acceleratedURL, type UpdateFile } from '../shared/updates';
import { downloadUpdate, safeDirectory, verifyUpdate, type FetchUpdate } from './update-download';

const magic = Buffer.from('STUDIOD1');
const maximumOperations = 16_384;
export const maximumDeltaBaseBytes = 256 * 1024 * 1024;

export type DeltaBase = { version: string; zip: string; size: number; sha256: string };
type Delta = UpdateFile & { baseSHA256: string };

function descriptor(file: UpdateFile, current: string): Delta | undefined {
  if (typeof file.delta !== 'object' || file.delta === null || Array.isArray(file.delta)) return;
  const delta = file.delta as Record<string, unknown>;
  if (
    delta.fromVersion !== current ||
    typeof delta.size !== 'number' ||
    !Number.isSafeInteger(delta.size) ||
    delta.size < 1 ||
    delta.size >= file.size ||
    typeof delta.baseSHA256 !== 'string' ||
    !/^[a-f0-9]{64}$/.test(delta.baseSHA256) ||
    typeof delta.sha256 !== 'string' ||
    !/^[a-f0-9]{64}$/.test(delta.sha256) ||
    delta.url !== file.url.slice(0, -4) + '-from-' + current + '.delta'
  )
    return;
  return { url: delta.url, size: delta.size, sha256: delta.sha256, baseSHA256: delta.baseSHA256 };
}

async function readExact(file: FileHandle, buffer: Buffer, size: number, position: number) {
  let offset = 0;
  while (offset < size) {
    const { bytesRead } = await file.read(buffer, offset, size - offset, position + offset);
    if (!bytesRead) throw Error('差量数据未完整读取');
    offset += bytesRead;
  }
}

async function rebuild(base: DeltaBase, patch: string, target: UpdateFile, directory: string, signal: AbortSignal) {
  const output = path.join(directory, randomUUID() + '.zip');
  let completed = false;
  let created = false;
  let destination: FileHandle | undefined;
  const original = await open(base.zip, constants.O_RDONLY | constants.O_NOFOLLOW);
  let input: FileHandle | undefined;
  try {
    input = await open(patch, constants.O_RDONLY | constants.O_NOFOLLOW);
    destination = await open(output, 'wx', 0o600);
    created = true;
    const size = (await input.stat()).size;
    const header = Buffer.alloc(12);
    await readExact(input, header, magic.length, 0);
    if (!header.subarray(0, magic.length).equals(magic)) throw Error('差量格式无效');
    let cursor = magic.length;
    let remaining = target.size;
    const bytes = Buffer.alloc(65536);
    for (let commands = 0; commands < maximumOperations; commands++) {
      signal.throwIfAborted();
      await readExact(input, header, 1, cursor++);
      const kind = header[0];
      if (kind === 255) {
        if (remaining !== 0 || cursor !== size) throw Error('差量输出不完整');
        await destination.sync();
        await destination.close();
        destination = undefined;
        await verifyUpdate(output, target, signal);
        completed = true;
        return output;
      }
      if (kind !== 0 && kind !== 1) throw Error('差量指令无效');
      const copy = kind === 1;
      const lengthBytes = copy ? 12 : 4;
      await readExact(input, header, lengthBytes, cursor);
      cursor += lengthBytes;
      const offset = copy ? header.readBigUInt64BE(0) : BigInt(cursor);
      const length = header.readUInt32BE(copy ? 8 : 0);
      if (length < 1 || length > remaining || offset + BigInt(length) > BigInt(copy ? base.size : size))
        throw Error('差量指令范围无效');
      let read = Number(offset);
      let left = length;
      while (left > 0) {
        signal.throwIfAborted();
        const count = Math.min(left, bytes.length);
        await readExact(copy ? original : input, bytes, count, read);
        await destination.writeFile(bytes.subarray(0, count));
        read += count;
        left -= count;
      }
      if (!copy) cursor += length;
      remaining -= length;
    }
    throw Error('差量指令过多');
  } finally {
    await Promise.allSettled([original.close(), input?.close(), destination?.close()]);
    if (created && !completed) await unlink(output).catch(() => {});
  }
}

/** Returns undefined only when the existing full-package path should be used. */
export async function downloadMacDelta(options: {
  base: DeltaBase | undefined;
  current: string;
  target: UpdateFile;
  directory: string;
  fetch: FetchUpdate;
  accelerated: boolean;
  signal: AbortSignal;
  progress: (percent: number, transferred: number, total: number) => void;
  fallback: () => void;
}): Promise<string | undefined> {
  const { base, target, signal, directory } = options;
  const delta = descriptor(target, options.current);
  if (!delta || !base || base.version !== options.current || base.sha256 !== delta.baseSHA256) return;
  let patch: string | undefined;
  try {
    safeDirectory(directory);
    if (path.dirname(base.zip) !== directory || base.size > maximumDeltaBaseBytes) throw Error('基准包无效');
    await verifyUpdate(base.zip, base, signal);
    patch = await downloadUpdate(
      options.fetch,
      options.accelerated ? acceleratedURL(delta.url) : delta.url,
      delta,
      directory,
      options.progress,
      signal
    );
    return await rebuild(base, patch, target, directory, signal);
  } catch {
    signal.throwIfAborted();
    options.fallback();
  } finally {
    if (patch) await unlink(patch).catch(() => {});
  }
}
