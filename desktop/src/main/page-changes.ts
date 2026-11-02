import type { WebContents } from 'electron';
import { resumeURL } from './recovery-store';

export type RecoveryChanges = {
  document: string;
  draft: number;
  viewport: number;
  conversation: number;
  known: boolean;
};

// This world has no Node/IPC bridge. Event handlers only invalidate small counters.
function readRecoveryChanges(): RecoveryChanges {
  const host = globalThis as typeof globalThis & { __studioRecoveryChanges?: () => RecoveryChanges };
  if (!host.__studioRecoveryChanges) {
    const composerSelector =
      '#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"]';
    const messageSelector = '[data-message-id],[data-message-author-role]';
    const state = { document: `${Date.now()}-${Math.random()}`, draft: 1, viewport: 1, conversation: 1 };
    let composer: HTMLElement | null = null;
    let message: Element | null = null;
    let previousDraft: string | undefined;
    let previousURL = location.href;
    const visual = () => {
      state.viewport++;
    };
    const draft = () => {
      state.draft++;
      visual();
    };
    const all = () => {
      draft();
      state.conversation++;
    };
    document.addEventListener('input', draft, true);
    document.addEventListener('change', draft, true);
    document.addEventListener('scroll', visual, { capture: true, passive: true });
    document.addEventListener('load', visual, true);
    document.addEventListener('error', visual, true);
    window.addEventListener('resize', visual, { passive: true });
    window.addEventListener('pageshow', all);
    document.fonts?.addEventListener('loadingdone', visual);
    if (document.documentElement) {
      new MutationObserver((records) => {
        for (const record of records.slice(0, 64)) {
          const target = record.target instanceof Element ? record.target : record.target.parentElement;
          if (target?.closest(composerSelector)) draft();
          else if (target?.closest(messageSelector)) {
            state.conversation++;
            visual();
          } else if (record.type === 'childList') all();
          else if (record.type === 'attributes') visual();
        }
        if (records.length > 64) all();
      }).observe(document.documentElement, {
        subtree: true,
        childList: true,
        characterData: true,
        attributes: true,
        attributeFilter: [
          'id',
          'data-message-id',
          'data-message-author-role',
          'src',
          'srcset',
          'class',
          'style',
          'hidden',
          'role'
        ]
      });
    }
    host.__studioRecoveryChanges = () => {
      if (location.href !== previousURL) {
        previousURL = location.href;
        all();
      }
      if (!composer?.isConnected) composer = document.querySelector<HTMLElement>(composerSelector);
      if (!message?.isConnected) message = document.querySelector(messageSelector);
      // Programmatic textarea assignments do not dispatch input or mutate the DOM.
      // Compare locally; no draft text crosses this lightweight probe's boundary.
      const value = composer instanceof HTMLTextAreaElement ? composer.value : composer?.textContent || '';
      if (previousDraft !== value) {
        previousDraft = value;
        draft();
      }
      // Raster frames can change without DOM mutations; retain periodic protected
      // capture on these pages rather than claiming the observer sees their pixels.
      const raster = document.querySelector('canvas,video');
      return { ...state, known: !!(composer || message) && !raster };
    };
  }
  return host.__studioRecoveryChanges();
}

export async function recoveryChanges(
  contents: WebContents,
  fixtureOrigin?: string
): Promise<RecoveryChanges | undefined> {
  if (contents.isDestroyed() || contents.isCrashed() || contents.isLoadingMainFrame()) return;
  const url = contents.getURL();
  if (!resumeURL(url, fixtureOrigin)) return;
  try {
    const result = await contents.executeJavaScriptInIsolatedWorld(1017, [
      { code: '(' + readRecoveryChanges.toString() + ')()' }
    ]);
    if (contents.isDestroyed() || contents.getURL() !== url) return;
    if (typeof result?.document !== 'string' || result.document.length > 100 || typeof result.known !== 'boolean')
      return;
    if (![result.draft, result.viewport, result.conversation].every((v) => Number.isSafeInteger(v) && v >= 0)) return;
    return result;
  } catch {
    // Unknown contexts keep the pre-existing protected capture path.
    return;
  }
}
