import type { HandoffJob } from './handoff';
import type { AssetRecord } from './assets';
import type { CapabilitySnapshot, TaskPromptOptions } from './capabilities';

export type WorkbenchPanel = 'assets' | 'skills' | 'agents';
export type AssetItem = AssetRecord & { thumbnailURL?: string };
export type AssetPreview = { assetId: string; mode: 'image' | 'html' | 'pdf' | 'markdown' | 'text' | 'table' | 'unsupported'; url?: string; text?: string };
export type AssetAction =
  | { type: 'import' | 'open-folder' }
  | { type: 'rename'; id: string; name: string }
  | { type: 'tags'; id: string; tags: string[] }
  | { type: 'favorite'; ids: string[]; favorite: boolean }
  | { type: 'trash'; ids: string[]; trashed: boolean }
  | { type: 'reveal' | 'export' | 'source' | 'pause' | 'resume' | 'cancel' | 'conversion-cancel'; id: string }
  | { type: 'agent-source'; id: string };
export type ConversionOptions = { format: 'PNG' | 'JPEG' | 'WEBP' | 'PDF' | 'HTML' | 'JSON' | 'CSV'; name?:string; width?: number; quality?: number; pages?: number[] };
export type ConversionResult = {assets:AssetItem[];failures:{name:string;error:string}[]};
export type HandoffRequest = { requestId: string; sourceProfileId: string; targetProfileId: string; assetIds: string[]; crossAccountConfirmed: boolean; skillId?: string; agentId?: string; language: string; instructions: string };
export type WorkbenchAPI = {
  assets: (profileId: string) => Promise<AssetItem[]>;
  act: (profileId: string, action: AssetAction) => Promise<void>;
  importDropped: (profileId: string, files: File[]) => Promise<void>;
  preview: (profileId: string, id: string) => Promise<AssetPreview>;
  readText: (profileId: string, id: string) => Promise<string>;
  capabilities: () => Promise<CapabilitySnapshot>;
  agent: (id: string) => Promise<string>;
  favoriteCapability: (id: string, favorite: boolean) => Promise<void>;
  convert: (profileId:string,ids:string[],options:ConversionOptions) => Promise<ConversionResult>;
  startHandoff:(request:HandoffRequest)=>Promise<HandoffJob>;
  handoffs:(profileId:string)=>Promise<HandoffJob[]>;
  cancelHandoff:(profileId:string,id:string)=>Promise<HandoffJob>;
  openHandoff:(profileId:string,id:string)=>Promise<void>;
  subscribeHandoffs:(handler:()=>void)=>()=>void;
  compose: (options: TaskPromptOptions) => Promise<string>;
  subscribe: (handler: (profileId: string) => void) => () => void;
};
