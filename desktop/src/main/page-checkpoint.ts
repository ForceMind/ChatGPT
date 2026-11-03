import type { WebContents } from 'electron';
import type { PageSnapshot, PageResume } from '../shared/recovery';
import { captureResume } from './page-resume';
import { captureSnapshot, snapshotAllowed } from './page-snapshot';
import { recoveryChanges, type RecoveryChanges } from './page-changes';
import { createHash } from 'node:crypto';
import type { PreparedRecoveryCheckpoint } from './recovery-checkpoint';

type Memory = {
  url: string;
  profileId: string;
  tabId: string;
  document?: string;
  draft?: number;
  viewport?: number;
  text?: number;
  image?: number;
  snapshotAttempt: number;
  critical?: string;
  alternative?: string;
};
const memories = new WeakMap<WebContents, Memory>();
const documents = new WeakMap<WebContents, { epoch: number }>();

export function checkpointDocument(contents: WebContents): number {
  let document = documents.get(contents);
  if (!document) {
    const tracked = { epoch: 0 };
    document = tracked;
    documents.set(contents, tracked);
    contents.on('did-start-navigation', (details) => {
      if (details.isMainFrame && !details.isSameDocument) tracked.epoch++;
    });
  }
  return document.epoch;
}

function criticalFingerprint(record: PageResume): string {
  return createHash('sha256')
    .update(JSON.stringify([record.profileId, record.tabId, record.url, record.draft, record.alternativeDraft ?? null]))
    .digest('hex');
}

/** Acknowledgements happen after the encrypted save, never when observation begins. */
export async function collectCheckpoint(
  contents: WebContents,
  profileId: string,
  tabId: string,
  previous: PageSnapshot | undefined,
  strict: boolean,
  available: () => boolean,
  fixtureOrigin?: string,
  alternativeDraft?: string
): Promise<PreparedRecoveryCheckpoint | undefined> {
  if (!available() || contents.isDestroyed()) return;
  const url = contents.getURL();
  const observed = await recoveryChanges(contents, fixtureOrigin);
  if (!available() || contents.isDestroyed() || contents.getURL() !== url) return;
  let memory = memories.get(contents);
  if (
    !memory ||
    memory.url !== url ||
    memory.document !== observed?.document ||
    memory.profileId !== profileId ||
    memory.tabId !== tabId
  ) {
    memory = { url, profileId, tabId, document: observed?.document, snapshotAttempt: 0 };
    memories.set(contents, memory);
  }
  const changes: RecoveryChanges | undefined = observed?.known ? observed : undefined;
  const alternative = alternativeDraft ? createHash('sha256').update(alternativeDraft).digest('hex') : '';
  const resumeChanged =
    !changes ||
    memory.draft !== changes.draft ||
    memory.viewport !== changes.viewport ||
    memory.alternative !== alternative;
  const textChanged = !changes || memory.text !== changes.conversation;
  const imageChanged = !changes || memory.image !== changes.viewport;
  const snapshotDue =
    snapshotAllowed(url, fixtureOrigin) &&
    (textChanged || imageChanged || !previous) &&
    (strict || Date.now() - memory.snapshotAttempt >= 30000);
  if (!strict && !resumeChanged && !snapshotDue) return;
  const record = await captureResume(contents, profileId, tabId, fixtureOrigin);
  if (!record || !available() || contents.isDestroyed() || contents.getURL() !== url) return;
  if (alternativeDraft && alternativeDraft !== record.draft) record.alternativeDraft = alternativeDraft;
  const page = previous?.url === record.url ? previous : undefined;
  if (page) record.page = page;
  const committed = memory;
  return {
    record,
    canMerge: () =>
      !!changes &&
      !strict &&
      memories.get(contents) === committed &&
      committed.critical === criticalFingerprint(record),
    acknowledge: () => {
      if (memories.get(contents) !== committed) return;
      committed.critical = criticalFingerprint(record);
      committed.alternative = alternative;
      committed.draft = changes?.draft;
      committed.viewport = changes?.viewport;
    },
    snapshot: async () => {
      if (!snapshotDue || !available() || contents.isDestroyed() || contents.getURL() !== url) return;
      committed.snapshotAttempt = Date.now();
      const next = await captureSnapshot(contents, fixtureOrigin, {
        previous: page,
        text: textChanged || !page,
        image: imageChanged || !page,
        available
      });
      if (!next || !available() || contents.isDestroyed() || contents.getURL() !== url) return;
      return {
        page: next,
        acknowledge: () => {
          if (memories.get(contents) !== committed) return;
          committed.text = changes?.conversation;
          committed.image = changes?.viewport;
        }
      };
    }
  };
}
