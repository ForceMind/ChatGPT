package com.forcemind.chatgpt.profiles;

import java.io.IOException;
import java.io.File;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

final class HandoffRequest {
    static final long MAX_FILE=100L*1024*1024,MAX_TOTAL=200L*1024*1024;
    static String string(JSONObject value,String key,int max,boolean required)throws Exception{
        Object raw=value.get(key);if(!(raw instanceof String))throw new IOException("任务文本参数无效");String text=(String)raw;
        if(text.length()>max||required&&text.trim().isEmpty()||text.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*"))throw new IOException("任务文本为空、过长或含控制字符");
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));return text;
    }
    static JSONObject normalize(JSONObject raw)throws Exception{
        JSONObject out=new JSONObject();
        for(String key:new String[]{"requestId","sourceAccountId","targetAccountId","sourceStorageId","targetStorageId"}){String id=string(raw,key,36,true);WorkspaceState.profile(id);out.put(key,id);}
        Object values=raw.get("assetIds");if(!(values instanceof JSONArray))throw new IOException("附件列表无效");JSONArray ids=(JSONArray)values,result=new JSONArray();Set<String> seen=new HashSet<>();
        if(ids.length()>20)throw new IOException("一次最多选择 20 个附件");for(int i=0;i<ids.length();i++){Object item=ids.get(i);if(!(item instanceof String))throw new IOException("附件标识无效");String id=(String)item;WorkspaceState.profile(id);if(!seen.add(id))throw new IOException("附件不能重复");result.put(id);}out.put("assetIds",result);
        if(!(raw.get("crossAccountConfirmed") instanceof Boolean))throw new IOException("跨账号确认无效");boolean confirmed=raw.getBoolean("crossAccountConfirmed");
        if(!out.getString("sourceAccountId").equals(out.getString("targetAccountId"))&&ids.length()>0&&!confirmed)throw new IOException("请明确确认将这些文件交给另一个账号");out.put("crossAccountConfirmed",confirmed);
        String skill=raw.has("skillId")?string(raw,"skillId",32,false):"";if(!skill.isEmpty()&&!skill.matches("skill-[0-9]{3}"))throw new IOException("Skill 标识无效");out.put("skillId",skill);
        String instructions=string(raw,"instructions",12000,false),language=string(raw,"language",80,true);if(skill.isEmpty()&&instructions.trim().isEmpty())throw new IOException("请选择 Skill 或填写处理要求");
        out.put("instructions",instructions);out.put("language",language);return out;
    }
    static String hash(byte[] bytes)throws Exception{StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    static String fingerprint(JSONObject request)throws Exception{return hash(normalize(request).toString().getBytes(StandardCharsets.UTF_8));}
    static void accounts(File files,JSONObject request)throws Exception{WorkspaceState state=WorkspaceFiles.read(files);WorkspaceState.Account source=state.find(request.getString("sourceAccountId")),target=state.find(request.getString("targetAccountId"));if(!source.storageId.equals(request.getString("sourceStorageId"))||!target.storageId.equals(request.getString("targetStorageId")))throw new IOException("账号登录空间已改变，请重新确认任务");}
}
