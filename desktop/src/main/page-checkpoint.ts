import type { WebContents } from 'electron';
import type { PageSnapshot, PageResume } from '../shared/recovery';
import { captureResume } from './page-resume';
import { captureSnapshot, snapshotAllowed } from './page-snapshot';
import { recoveryChanges, type RecoveryChanges } from './page-changes';

type Memory = {
  url: string;
  document?: string;
  draft?: number;
  viewport?: number;
  text?: number;
  image?: number;
  snapshotAttempt: number;
};
const memories = new WeakMap<WebContents, Memory>();

/** Acknowledgements happen after the encrypted save, never when observation begins. */
export async function collectCheckpoint(
  contents: WebContents,
  profileId: string,
  tabId: string,
  previous: PageSnapshot | undefined,
  strict: boolean,
  available: () => boolean,
  fixtureOrigin?: string
): Promise<
  | {
      record: PageResume;
      acknowledge: () => void;
      snapshot: () => Promise<{ page: PageSnapshot; acknowledge: () => void } | undefined>;
    }
  | undefined
> {
  if (!available() || contents.isDestroyed()) return;
  const url = contents.getURL();
  const observed = await recoveryChanges(contents, fixtureOrigin);
  if (!available() || contents.isDestroyed() || contents.getURL() !== url) return;
  let memory = memories.get(contents);
  if (!memory || memory.url !== url || memory.document !== observed?.document) {
    memory = { url, document: observed?.document, snapshotAttempt: 0 };
    memories.set(contents, memory);
  }
  const changes: RecoveryChanges | undefined = observed?.known ? observed : undefined;
  const resumeChanged = !changes || memory.draft !== changes.draft || memory.viewport !== changes.viewport;
  const textChanged = !changes || memory.text !== changes.conversation;
  const imageChanged = !changes || memory.image !== changes.viewport;
  const snapshotDue =
    snapshotAllowed(url, fixtureOrigin) &&
    (textChanged || imageChanged || !previous) &&
    (strict || Date.now() - memory.snapshotAttempt >= 30000);
  if (!strict && !resumeChanged && !snapshotDue) return;
  const record = await captureResume(contents, profileId, tabId, fixtureOrigin);
  if (!record || !available() || contents.isDestroyed() || contents.getURL() !== url) return;
  const page = previous?.url === record.url ? previous : undefined;
  if (page) record.page = page;
  const committed = memory;
  return {
    record,
    acknowledge: () => {
      if (memories.get(contents) !== committed) return;
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
