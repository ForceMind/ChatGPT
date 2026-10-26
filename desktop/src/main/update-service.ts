import type {UpdateStatus} from '../shared/updates';
export type UpdateSummary={version:string;publishedAt:string;notes:string};
/** The shell shares only user-visible state; package validation stays platform-specific. */
export interface UpdateService{
 readonly status:UpdateStatus;readonly manifest?:UpdateSummary;
 check(now?:number):Promise<UpdateSummary|undefined>;
 start(check:(manual?:boolean)=>void):void;resume(check:()=>void):void;
 download(accelerated?:boolean):Promise<void>;stopForQuit():Promise<void>;
 installOnQuit(allowRelaunch?:boolean):Promise<void>;restartAfterInstall(background?:boolean):void;
 cancelRestart():void;dispose():void;
 sessionEnd?():void;
 idleChoiceMade?():boolean;setIdleAllowed?(allowed:boolean):void;setIdleDeadline?(deadline?:number):void;
 exportPackage?(destination:string):Promise<void>;readonly suggestedFilename?:string;
}
