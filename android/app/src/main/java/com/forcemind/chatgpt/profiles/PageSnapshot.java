package com.forcemind.chatgpt.profiles;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Bounded, inert conversation text and an optional locally captured JPEG. */
final class PageSnapshot {
    static final int MAX_MESSAGES=300,MAX_TEXT=60000,MAX_IMAGE_BYTES=90000,MAX_IMAGE_TEXT=120000;
    static boolean conversation(String address){
        try{
            if(!address.equals(PageRecoveryRecord.url(address)))return false;
            String path=URI.create(address).getPath();
            if(path==null||!path.matches("^/(?:c/[^/]+|g/[^/]+/c/[^/]+)/?$"))return false;if(path.endsWith("/"))path=path.substring(0,path.length()-1);
            String id=path.substring(path.lastIndexOf('/')+1);
            return !id.toLowerCase(Locale.ROOT).startsWith("local-chatgpt:");
        }catch(Exception error){return false;}
    }
    static JSONObject normalize(JSONObject raw,String address)throws Exception{
        if(!conversation(address))throw new IOException("此页面不保存对话快照");
        String title=HandoffRequest.string(raw,"title",240,false);Object truncated=raw.get("truncated");
        if(!(truncated instanceof Boolean))throw new IOException("快照状态无效");
        Object time=raw.get("capturedAt");if(!(time instanceof Number)||((Number)time).doubleValue()!=((Number)time).longValue()||((Number)time).longValue()<0)throw new IOException("快照时间无效");
        JSONArray input=raw.getJSONArray("messages"),messages=new JSONArray();if(input.length()==0||input.length()>MAX_MESSAGES)throw new IOException("快照消息数量无效");int total=0;
        for(int i=0;i<input.length();i++){
            JSONObject item=input.getJSONObject(i);String role=HandoffRequest.string(item,"role",16,true),text=HandoffRequest.string(item,"text",MAX_TEXT,true);
            if(!Arrays.asList("user","assistant","message").contains(role)||(total+=text.length())>MAX_TEXT)throw new IOException("快照消息无效");
            JSONObject message=new JSONObject().put("role",role).put("text",text);if(item.has("id"))message.put("id",HandoffRequest.string(item,"id",160,true));messages.put(message);
        }
        JSONObject out=new JSONObject().put("title",title).put("messages",messages).put("truncated",truncated).put("capturedAt",time);
        if(raw.has("image")){
            String image=HandoffRequest.string(raw,"image",MAX_IMAGE_TEXT,true);byte[] bytes;
            try{bytes=Base64.getDecoder().decode(image);}catch(IllegalArgumentException error){throw new IOException("快照画面无效",error);}
            if(bytes.length<4||bytes.length>MAX_IMAGE_BYTES||(bytes[0]&255)!=255||(bytes[1]&255)!=216||(bytes[bytes.length-2]&255)!=255||(bytes[bytes.length-1]&255)!=217)throw new IOException("快照画面无效");
            Object imageTime=raw.has("imageCapturedAt")?raw.get("imageCapturedAt"):time;if(!(imageTime instanceof Number)||((Number)imageTime).doubleValue()!=((Number)imageTime).longValue()||((Number)imageTime).longValue()<0)throw new IOException("快照画面时间无效");out.put("image",image).put("imageCapturedAt",imageTime);
        }
        return out;
    }
    static String contentFingerprint(JSONObject page)throws Exception{
        JSONObject value=new JSONObject(page.toString());value.remove("capturedAt");value.remove("image");value.remove("imageCapturedAt");
        return HandoffRequest.hash(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
