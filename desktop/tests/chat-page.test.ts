import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createContext, runInContext } from 'node:vm';
import { EventEmitter } from 'node:events';
import type { WebContents } from 'electron';
import { ChatPage } from '../src/main/chat-page';

// Keep the page's DOM accessible through Electron's page execution while the
// debugger's default evaluation context cannot return a DOM node.
function pageWithUnavailableDebuggerNode(options: { draft?: string; refuseFocus?: boolean } = {}) {
  const inserted: string[] = [];
  let activeElement: unknown;
  class Textarea {
    value = options.draft || '';
    disabled = false;
    readOnly = false;
    getClientRects() {
      return [{}];
    }
    closest() {
      return form;
    }
    contains(node: unknown) {
      return node === this;
    }
    focus() {
      if (!options.refuseFocus) activeElement = this;
    }
    setSelectionRange() {}
  }
  const editor = new Textarea();
  const form = { querySelectorAll: () => [], querySelector: () => null };
  const window = new EventEmitter() as EventEmitter & Record<string, unknown>;
  Object.assign(window, {
    addEventListener: window.on.bind(window),
    removeEventListener: window.off.bind(window)
  });
  const document = {
    get activeElement() {
      return activeElement;
    },
    querySelectorAll: (selector: string) => (selector.startsWith('#prompt-textarea') ? [editor] : []),
    querySelector: () => null
  };
  const context = createContext({
    window,
    document,
    HTMLTextAreaElement: Textarea,
    location: { href: 'https://chatgpt.com/', pathname: '/' },
    // tsx preserves function names with this helper. Packaged esbuild output
    // uses native functions and has no reference to the helper.
    __name: (fn: unknown) => fn
  });
  let attached = false;
  const debug = Object.assign(new EventEmitter(), {
    isAttached: () => attached,
    attach: () => {
      attached = true;
    },
    detach: () => {
      attached = false;
    },
    sendCommand: async (method: string, params: { text?: string }) => {
      if (method === 'Runtime.evaluate') return { result: { type: 'undefined' } };
      if (method === 'Input.insertText') {
        assert.equal(activeElement, editor, 'native input must target the checked composer');
        inserted.push(params.text!);
        editor.value += params.text;
        return {};
      }
      throw Error('Unexpected debugger command: ' + method);
    }
  });
  const contents = {
    debugger: debug,
    isDestroyed: () => false,
    isLoadingMainFrame: () => false,
    getURL: () => 'https://chatgpt.com/',
    getBackgroundThrottling: () => false,
    setBackgroundThrottling: () => {},
    executeJavaScript: async (source: string) => {
      try {
        return runInContext(source, context);
      } catch {
        // Electron can replace a renderer exception with this generic error.
        throw Error('Script failed to execute, this normally means an error was thrown.');
      }
    }
  } as unknown as WebContents;
  const page = new ChatPage(contents, undefined, () => {});
  return { page, editor, inserted };
}

test('draft uses the checked page composer when the debugger cannot return its node', async () => {
  const { page, editor, inserted } = pageWithUnavailableDebuggerNode();
  const prompt = '完整处理要求\n保留中文、emoji 🐈 和第二行。';
  try {
    await page.draft(prompt);
    assert.equal(editor.value, prompt);
    assert.deepEqual(inserted, [prompt]);
  } finally {
    page.dispose();
  }
});

test('draft reuses the full task prompt already present in the fresh task editor', async () => {
  const prompt = '自动任务要求\n保留完整指令';
  const { page, editor, inserted } = pageWithUnavailableDebuggerNode({ draft: prompt });
  const progress: number[] = [];
  try {
    await page.ready(prompt);
    await page.draft(prompt, (written) => progress.push(written));
    assert.equal(editor.value, prompt);
    assert.deepEqual(inserted, []);
    assert.deepEqual(progress, [prompt.length]);
  } finally {
    page.dispose();
  }
});

test('draft preserves an existing user draft without inserting text', async () => {
  const { page, editor, inserted } = pageWithUnavailableDebuggerNode({ draft: '保留我的草稿' });
  try {
    await assert.rejects(page.ready('自动任务要求'), /已有内容/);
    await assert.rejects(page.draft('自动任务要求'), /已有内容/);
    assert.equal(editor.value, '保留我的草稿');
    assert.deepEqual(inserted, []);
  } finally {
    page.dispose();
  }
});

test('draft stops before native input if the page composer cannot take focus', async () => {
  const { page, editor, inserted } = pageWithUnavailableDebuggerNode({ refuseFocus: true });
  try {
    await assert.rejects(page.draft('自动任务要求'), /聚焦/);
    assert.equal(editor.value, '');
    assert.deepEqual(inserted, []);
  } finally {
    page.dispose();
  }
});
