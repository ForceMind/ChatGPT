/** Composer-only DOM adapter. No framework internals, history or private APIs. */
export function chatDOM() {
  const visible = (e: Element) => !!e.getClientRects().length;
  const candidates = [
    ...document.querySelectorAll<HTMLElement>(
      '#prompt-textarea,textarea[data-testid="prompt-textarea"],textarea[placeholder*="Message"],textarea[placeholder*="询问"],[data-type="unified-composer"] [contenteditable="true"][role="textbox"],.ProseMirror[contenteditable="true"][role="textbox"][aria-label="询问 ChatGPT"],.ProseMirror[contenteditable="true"][role="textbox"][aria-label="Ask ChatGPT"]'
    )
  ];
  const editable = (e: HTMLElement) =>
    e instanceof HTMLTextAreaElement ? !e.disabled && !e.readOnly : e.isContentEditable;
  const composer = candidates
    .flatMap((e) => (editable(e) ? [e] : [...e.querySelectorAll<HTMLElement>('textarea,[contenteditable="true"]')]))
    .find((e) => visible(e) && editable(e));
  const addSelector =
    'button[data-testid="composer-plus-btn"],button[aria-label="添加文件等内容"],button[aria-label="添加文件等"],button[aria-label="Add files and more"]';
  let controlGroup = composer?.parentElement;
  while (controlGroup && !controlGroup.matches('main,body,html') && !controlGroup.querySelector(addSelector))
    controlGroup = controlGroup.parentElement;
  if (controlGroup?.matches('main,body,html')) controlGroup = null;
  const scope =
    composer?.closest('[data-type="unified-composer"]') ||
    composer?.closest('form') ||
    controlGroup ||
    composer?.parentElement?.parentElement;
  const buttons = [
    ...(scope?.querySelectorAll<HTMLButtonElement>(
      'button[data-testid="send-button"],button[aria-label="Send prompt"],button[aria-label="发送提示"],button[aria-label="发送消息"],button[aria-label="Send message"],button[aria-label="发送"]'
    ) || [])
  ].filter(visible);
  const send = buttons.find((b) => !b.disabled && b.getAttribute('aria-disabled') !== 'true');
  // innerText inserts layout-dependent blank lines between rich-text paragraphs.
  // Read paragraph/BR boundaries explicitly so multiline instructions compare
  // consistently without collapsing meaningful internal whitespace.
  const read = (node: Node): string => {
    let result = '';
    const children = [...node.childNodes];
    children.forEach((child, index) => {
      if (child.nodeType === Node.TEXT_NODE) {
        result += child.nodeValue || '';
        return;
      }
      if (!(child instanceof HTMLElement)) return;
      if (child.tagName === 'BR') {
        if (!child.classList.contains('ProseMirror-trailingBreak')) result += '\n';
        return;
      }
      const block = /^(P|DIV|LI|PRE|BLOCKQUOTE)$/.test(child.tagName);
      if (block && result && !result.endsWith('\n')) result += '\n';
      result += read(child);
      if (block && index < children.length - 1) result += '\n';
    });
    return result;
  };
  const text = composer instanceof HTMLTextAreaElement ? composer.value : composer ? read(composer) : '';
  const inputs = [...document.querySelectorAll<HTMLInputElement>('input[type="file"]')].filter(
    (i) => !i.webkitdirectory && !i.accept.startsWith('image/') && !i.disabled
  );
  return {
    composer,
    scope,
    send,
    text,
    input: inputs[0],
    add: [...(scope?.querySelectorAll<HTMLButtonElement>(addSelector) || [])].find(
      (button) => visible(button) && !button.disabled
    ),
    sendPresent: buttons.length > 0,
    focused: !!composer && (document.activeElement === composer || composer.contains(document.activeElement))
  };
}
export const normalizedPrompt = (text: string): string =>
  text
    .replace(/\r\n?/g, '\n')
    .replace(/\u00a0/g, ' ')
    .trim();
