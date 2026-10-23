import type { HandoffRequest } from './workbench';
export type HandoffStatus='preparing'|'opening'|'uploading'|'drafting'|'sending'|'submitted'|'failed'|'uncertain'|'cancelled'|'interrupted';
export type HandoffFile={assetId:string;profileId:string;name:string;uploadName:string;mime:string;uploadMime:string;size:number;sha256:string};
export const handoffStages=['files','conversation','attachments','instructions','ready','send','confirmation'] as const;
export type HandoffStage=typeof handoffStages[number];
export const handoffStageNames:Record<HandoffStage,string>={files:'校验文件',conversation:'新建对话',attachments:'确认附件',instructions:'写入指令',ready:'检查发送条件',send:'发送一次',confirmation:'网页确认'};
export type HandoffProgress={stage:HandoffStage;startedAt:number;completedAt?:number;note?:string};
export type HandoffJob={
  id:string;request:HandoffRequest;fingerprint:string;sourceProfileName:string;targetProfileName:string;label:string;
  status:HandoffStatus;message:string;createdAt:number;updatedAt:number;files:HandoffFile[];
  catalogCommit:string;tabId?:string;conversationURL?:string;
  progress?:HandoffProgress[];
};
export const handoffStatusNames:Record<HandoffStatus,string>={preparing:'校验文件',opening:'新建对话',uploading:'上传附件',drafting:'准备指令',sending:'正在发送',submitted:'已发送',failed:'未能发送',uncertain:'结果待确认',cancelled:'已取消',interrupted:'已中断'};
