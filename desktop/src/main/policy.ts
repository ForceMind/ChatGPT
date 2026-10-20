import { HOME } from '../shared/model';
const CHAT_HOSTS = new Set(['chatgpt.com', 'chat.openai.com']);
const AUTH_HOSTS = new Set(['auth.openai.com', 'auth0.openai.com', 'accounts.google.com', 'appleid.apple.com', 'idmsa.apple.com', 'login.live.com', 'login.microsoftonline.com', 'login.microsoft.com', 'account.live.com']);
export function isWebURL(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 8192) return false;
  try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password; } catch { return false; }
}
export function isChatURL(value: string): boolean { try { return isWebURL(value) && CHAT_HOSTS.has(new URL(value).hostname); } catch { return false; } }
export function isAllowedURL(value: unknown, fixtureOrigin?: string): value is string {
  if (typeof value !== 'string' || value.length > 8192) return false;
  try {
    const u = new URL(value);
    if (u.username || u.password) return false;
    if (fixtureOrigin && u.origin === fixtureOrigin && u.hostname === '127.0.0.1' && u.protocol === 'http:') return true;
    return isWebURL(value) && (CHAT_HOSTS.has(u.hostname) || AUTH_HOSTS.has(u.hostname));
  } catch { return false; }
}
export function partitionFor(id: string): string {
  if (!/^[a-f0-9-]{36}$/.test(id)) throw new Error('无效的账号标识');
  return `persist:profile-${id}`;
}
export function homeURL(fixtureOrigin?: string): string { return fixtureOrigin ? `${fixtureOrigin}/` : HOME; }
