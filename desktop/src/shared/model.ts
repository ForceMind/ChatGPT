export const APP_VERSION = '3.0.0-alpha.1';
export const SHELL_HEIGHT = 46;
export const HOME = 'https://chatgpt.com/';
export const COLORS = ['#32745d', '#5275a0', '#9b6eab', '#bb8050', '#b05f73', '#667485'] as const;
export type Profile = { id: string; name: string; email: string; color: string; activeTabId: string; createdAt: number };
export type Tab = { id: string; profileId: string; url: string; title: string; pinned: boolean };
export type SavedState = { schema: 1; profiles: Profile[]; tabs: Tab[]; activeProfileId: string; sidebarOpen: boolean; closedTabs: Tab[]; bounds: { width: number; height: number; x?: number; y?: number } };
export type TabStatus = { loading: boolean; error?: string; canGoBack: boolean; canGoForward: boolean; crashed?: boolean; url?: string };
export type Snapshot = SavedState & { version: string; tabStatus: Record<string, TabStatus>; fullScreen: boolean; testMode: boolean; notice?: string; overlayImage?: string };
export type Command =
  | { type: 'profile:add'; name: string; email: string; color: string }
  | { type: 'profile:edit'; id: string; name: string; email: string; color: string }
  | { type: 'profile:activate' | 'profile:remove' | 'profile:clear'; id: string }
  | { type: 'profile:move'; id: string; direction: -1 | 1 }
  | { type: 'tab:new'; url?: string }
  | { type: 'tab:activate' | 'tab:close' | 'tab:pin' | 'tab:menu'; id: string }
  | { type: 'tab:reorder'; ids: string[] }
  | { type: 'tab:restore' | 'back' | 'forward' | 'reload' | 'home' | 'fullscreen' | 'maximize' | 'external' }
  | { type: 'overlay'; open: boolean };
export type Reply = { ok: true } | { ok: false; error: string };
export type Bridge = { snapshot: () => Promise<Snapshot>; command: (command: Command) => Promise<Reply>; subscribe: (handler: (state: Snapshot) => void) => () => void; onShortcut: (handler: (key: string) => void) => () => void };
