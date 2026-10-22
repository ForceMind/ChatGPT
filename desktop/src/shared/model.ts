import type {WorkspaceState,WorkbenchViewState} from './recovery';
import type { LoginDiagnosticsAPI } from './diagnostics';
import type { WorkbenchAPI, WorkbenchPanel } from './workbench';
export const APP_VERSION = '3.0.0-alpha.6';
export const desktopWorkspaceEnabled = (platform?:string):boolean => platform === 'darwin' || platform === 'win32';
export const SHELL_HEIGHT = 46;
export const HOME = 'https://chatgpt.com/';
export const COLORS = ['#32745d', '#5275a0', '#9b6eab', '#bb8050', '#b05f73', '#667485'] as const;
export type Profile = { id: string; name: string; email: string; color: string; activeTabId: string; createdAt: number; language?:string; timeZone?:string; recoveryEpoch?:number; workspace?:WorkspaceState };
export type Tab = { id: string; profileId: string; url: string; title: string; pinned: boolean; loaded?:boolean; closedPosition?:number };
export type SavedState = { schema: 1; profiles: Profile[]; tabs: Tab[]; activeProfileId: string; sidebarOpen: boolean; windowMode?:{maximized:boolean;fullscreen:boolean}; closedTabs: Tab[]; capabilityFavorites?: string[]; bounds: { width: number; height: number; x?: number; y?: number } };
export type TabStatus = { loading: boolean; error?: string; canGoBack: boolean; canGoForward: boolean; crashed?: boolean; url?: string };
export type Snapshot = SavedState & { version: string; platform: string; tabStatus: Record<string, TabStatus>; fullScreen: boolean; testMode: boolean; notice?: string; overlayImage?: string; panel?: WorkbenchPanel; assetError?: string };
export type Command =
  | { type: 'profile:add'; name: string; email: string; color: string; language?:string; timeZone?:string }
  | { type: 'profile:edit'; id: string; name: string; email: string; color: string; language?:string; timeZone?:string }
  | { type: 'profile:activate' | 'profile:remove' | 'profile:clear'; id: string }
  | { type: 'profile:move'; id: string; direction: -1 | 1 }
  | { type: 'tab:new'; url?: string }
  | { type: 'tab:activate' | 'tab:close' | 'tab:pin' | 'tab:menu'; id: string }
  | { type: 'tab:reorder'; ids: string[] }
  | { type: 'tab:restore' | 'back' | 'forward' | 'reload' | 'home' | 'fullscreen' | 'maximize' | 'external' }
  | { type: 'overlay'; open: boolean }
  | { type: 'panel'; panel?: WorkbenchPanel };
export type Reply = { ok: true } | { ok: false; error: string };
export type Bridge = { snapshot: () => Promise<Snapshot>; command: (command: Command) => Promise<Reply>; subscribe: (handler: (state: Snapshot) => void) => () => void; onShortcut: (handler: (key: string) => void) => () => void; saveWorkbench:(profileId:string,state:WorkbenchViewState,epoch?:number)=>Promise<void>; workbench: WorkbenchAPI; diagnostics: LoginDiagnosticsAPI };
