package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Only composer text and reading coordinates; never login fields or page HTML. */
final class PageRecoveryRecord {
    static String url(String value){
        if(!NavigationPolicy.chat(value))return null;
        try{
            URI uri=URI.create(value);String path=uri.getRawPath();
            if(path.matches("^/(auth|api|backend-api|login|logout|signup|signin)(/.*)?$"))return null;
            String query=uri.getRawQuery();if(query!=null)for(String pair:query.split("&")){String[] kv=pair.split("=",2);if(URLDecoder.decode(kv[0],"UTF-8").equals("temporary-chat")&&kv.length==2&&URLDecoder.decode(kv[1],"UTF-8").equalsIgnoreCase("true"))return null;}
            return NavigationPolicy.restorable(value);
        }catch(Exception e){return null;}
    }
    static JSONObject normalize(JSONObject raw)throws Exception{
        if(raw.has("schema")&&(!(raw.get("schema") instanceof Number)||((Number)raw.get("schema")).doubleValue()!=1))throw new IOException("恢复记录版本无效");
        JSONObject out=new JSONObject().put("schema",1);
        for(String key:new String[]{"accountId","storageId","tabId"}){String id=HandoffRequest.string(raw,key,36,true);WorkspaceState.profile(id);out.put(key,id);}
        String address=HandoffRequest.string(raw,"url",8192,true);if(!address.equals(url(address)))throw new IOException("此页面不能保存恢复记录");out.put("url",address);
        out.put("draft",HandoffRequest.string(raw,"draft",100000,false));
        if(raw.has("alternativeDraft"))out.put("alternativeDraft",HandoffRequest.string(raw,"alternativeDraft",100000,false));
        for(String key:new String[]{"scroll","ratio"}){Object value=raw.get(key);if(!(value instanceof Number))throw new IOException("阅读位置无效");double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n<0||n>(key.equals("ratio")?1:100000000))throw new IOException("阅读位置无效");out.put(key,n);}
        Object time=raw.get("capturedAt");if(!(time instanceof Number)||((Number)time).doubleValue()!=((Number)time).longValue()||((Number)time).longValue()<0)throw new IOException("恢复时间无效");out.put("capturedAt",time);
        if(raw.has("anchor")){JSONObject anchor=raw.getJSONObject("anchor");String kind=anchor.getString("kind"),value=HandoffRequest.string(anchor,"value",160,true);Object offset=anchor.get("offset");if(!Arrays.asList("id","message").contains(kind)||!(offset instanceof Number)||!Double.isFinite(((Number)offset).doubleValue())||Math.abs(((Number)offset).doubleValue())>100000)throw new IOException("阅读锚点无效");out.put("anchor",new JSONObject().put("kind",kind).put("value",value).put("offset",offset));}
        return out;
    }
    static void available(File files,String account,String storage,String tab)throws Exception{
        WorkspaceState.Account owner=WorkspaceFiles.read(files).find(account);if(!owner.storageId.equals(storage))throw new IOException("账号登录空间已改变");
        if(owner.tabs.stream().noneMatch(t->t.id.equals(tab))&&owner.closedTabs.stream().noneMatch(t->t.id.equals(tab)))throw new IOException("标签页已不在恢复列表中");
        for(String name:new String[]{"revoked-profiles.json","revoked-profiles.json.bak"}){File f=new File(files,name);if(!f.exists())continue;byte[] bytes=Files.readAllBytes(HandoffFiles.regular(files,name,1024*1024).toPath());JSONArray revoked=new JSONArray(new String(bytes,StandardCharsets.UTF_8));for(int i=0;i<revoked.length();i++)if(storage.equals(revoked.getString(i)))throw new IOException("此登录空间已撤销");}
    }
}
