package com.forcemind.chatgpt.profiles;

final class HandoffStatus {
    static String name(String state){switch(state){case "preparing":return "校验文件";case "ready":return "等待打开";case "opening":return "新建对话";case "uploading":return "上传附件";case "drafting":return "准备指令";case "sending":return "正在发送";case "submitted":return "已发送";case "failed":return "未能发送";case "uncertain":return "结果待确认";case "cancelled":return "已取消";case "interrupted":return "已中断";default:return "未知状态";}}
}
