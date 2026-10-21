export const LINK_KINDS = ['navigation', 'redirect', 'popup', 'login-page'] as const;
export type LinkKind = typeof LINK_KINDS[number];
export const LINK_RESULTS = ['blocked', 'cancelled', 'browser-opened', 'browser-failed', 'allowed'] as const;
export type LinkResult = typeof LINK_RESULTS[number];
export type LinkRecord = { id:string; time:number; profileId:string; kind:LinkKind; result:LinkResult; destination:string; source:string; reason:string };
export type LoginDiagnosticsAPI = { list:()=>Promise<LinkRecord[]>; clear:()=>Promise<void>; subscribe:(handler:()=>void)=>()=>void };
export const LINK_KIND_LABEL:Record<LinkKind,string> = {navigation:'网页链接',redirect:'自动跳转',popup:'弹出窗口','login-page':'登录页面'};
export const LINK_RESULT_LABEL:Record<LinkResult,string> = {blocked:'已拦截',cancelled:'已取消打开', 'browser-opened':'已在系统浏览器打开','browser-failed':'系统浏览器打开失败',allowed:'允许访问'};
