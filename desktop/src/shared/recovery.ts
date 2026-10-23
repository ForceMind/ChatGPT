import type {WorkbenchPanel} from './workbench';
export type WorkbenchViewState={filter:string;query:string;sort:'recent'|'name'|'size';layout:'grid'|'list';selected?:string;libraryKind:'skills'|'agents';libraryFilter:string;libraryQuery:string;librarySelected?:string;favoritesOnly:boolean;preview?:string;scroll:number;libraryScroll:number};
export type WorkspaceState={panel?:WorkbenchPanel;workbench?:WorkbenchViewState};
export type PageSnapshot={url:string;title:string;messages:{role:'user'|'assistant'|'message';text:string}[];truncated:boolean;image?:string;capturedAt:number};
export type PageResume={schema:1;profileId:string;tabId:string;url:string;draft:string;page?:PageSnapshot;alternativeDraft?:string;scroll:number;ratio:number;anchor?:{kind:'id'|'message';value:string;offset:number};capturedAt:number};
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const label=(v:unknown,n:number)=>typeof v==='string'&&v.length<=n&&!/[\u0000-\u001f\u007f]/.test(v);
export const recoveryId=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
const position=(v:unknown)=>typeof v==='number'&&Number.isFinite(v)&&v>=0&&v<=100_000_000;
export function validWorkspace(v:unknown):v is WorkspaceState{
 if(!object(v)||Object.keys(v).some(k=>!['panel','workbench'].includes(k))||v.panel!==undefined&&!['assets','skills','agents'].includes(v.panel as string))return false;
 if(v.workbench===undefined)return true;const w=v.workbench;if(!object(w)||Object.keys(w).some(k=>!['filter','query','sort','layout','selected','libraryKind','libraryFilter','libraryQuery','librarySelected','favoritesOnly','preview','scroll','libraryScroll'].includes(k)))return false;
 return ['all','image','web','document','data','favorite','converted','trash'].includes(w.filter as string)&&label(w.query,300)&&['recent','name','size'].includes(w.sort as string)&&['grid','list'].includes(w.layout as string)&&(w.selected===undefined||recoveryId(w.selected))&&['skills','agents'].includes(w.libraryKind as string)&&label(w.libraryFilter,100)&&label(w.libraryQuery,300)&&(w.librarySelected===undefined||typeof w.librarySelected==='string'&&/^(skill|agent)-\d{3}$/.test(w.librarySelected))&&typeof w.favoritesOnly==='boolean'&&(w.preview===undefined||recoveryId(w.preview))&&position(w.scroll)&&position(w.libraryScroll);
}
export function validResume(v:unknown):v is PageResume{
 if(!object(v)||v.schema!==1||!recoveryId(v.profileId)||!recoveryId(v.tabId)||typeof v.url!=='string'||v.url.length>8192||typeof v.draft!=='string'||v.draft.length>100_000||!position(v.scroll)||typeof v.ratio!=='number'||v.ratio<0||v.ratio>1||!Number.isFinite(v.ratio)||typeof v.capturedAt!=='number'||!Number.isSafeInteger(v.capturedAt)||v.capturedAt<0)return false;
 if(v.page!==undefined&&(!validPageSnapshot(v.page)||v.page.url!==v.url))return false;
 if(v.alternativeDraft!==undefined&&(typeof v.alternativeDraft!=='string'||v.alternativeDraft.length>100000))return false;
 if(v.anchor!==undefined){const a=v.anchor;if(!object(a)||!['id','message'].includes(a.kind as string)||!label(a.value,160)||!a.value||typeof a.offset!=='number'||!Number.isFinite(a.offset)||Math.abs(a.offset)>100000)return false;}
 return true;
}

export function validPageSnapshot(v:unknown):v is PageSnapshot{
 if(!object(v)||typeof v.url!=='string'||v.url.length>8192||!label(v.title,240)||typeof v.truncated!=='boolean'||!Number.isSafeInteger(v.capturedAt)||(v.capturedAt as number)<0||!Array.isArray(v.messages)||!v.messages.length||v.messages.length>300)return false;
 let length=0;for(const m of v.messages){if(!object(m)||!['user','assistant','message'].includes(m.role as string)||typeof m.text!=='string')return false;length+=m.text.length;}
 return length<=100000&&(v.image===undefined||typeof v.image==='string'&&v.image.length<=400024&&/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(v.image));
}
