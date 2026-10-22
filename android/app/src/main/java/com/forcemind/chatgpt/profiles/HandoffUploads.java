package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Process-local, revocable capabilities. No URI is valid after process restart. */
final class HandoffUploads {
    static final class FileInfo {final File file;final JSONObject metadata;FileInfo(File file,JSONObject metadata){this.file=file;this.metadata=metadata;}}
    private static final class Grant {final String token;final Map<String,FileInfo> files;final BooleanSupplier live;Grant(String token,Map<String,FileInfo> files,BooleanSupplier live){this.token=token;this.files=files;this.live=live;}}
    private static final Map<String,Grant> GRANTS=new HashMap<>();
    static synchronized Uri[] grant(Context context,JSONObject job,JSONObject payload,BooleanSupplier live)throws Exception{
        String id=job.getString("id"),token=payload.getString("uploadToken");WorkspaceState.profile(token);if(GRANTS.containsKey(id))throw new IOException("附件授权已经发出");Map<String,FileInfo> files=new HashMap<>();JSONArray entries=job.getJSONArray("files");Uri[] uris=new Uri[entries.length()];File directory=HandoffFiles.staging(context.getFilesDir(),id,false);
        for(int i=0;i<entries.length();i++){JSONObject entry=new JSONObject(entries.getJSONObject(i).toString());String stage=entry.getString("stageId");WorkspaceState.profile(stage);File file=HandoffFiles.regular(directory,stage+".bin",HandoffRequest.MAX_FILE);if(file.length()!=entry.getLong("size"))throw new IOException("临时附件已改变");files.put(stage,new FileInfo(file,entry));uris[i]=new Uri.Builder().scheme("content").authority(context.getPackageName()+".handoff").appendPath(id).appendPath(token).appendPath(stage).build();}
        GRANTS.put(id,new Grant(token,files,live));return uris;
    }
    static synchronized FileInfo resolve(Context context,Uri uri)throws Exception{
        if(!"content".equals(uri.getScheme())||!(context.getPackageName()+".handoff").equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null)throw new FileNotFoundException("附件地址无效");List<String> parts=uri.getPathSegments();if(parts.size()!=3)throw new FileNotFoundException("附件地址无效");for(String part:parts)WorkspaceState.profile(part);
        Grant grant=GRANTS.get(parts.get(0));if(grant==null||!grant.token.equals(parts.get(1))||!grant.live.getAsBoolean())throw new FileNotFoundException("附件授权已撤销");FileInfo info=grant.files.get(parts.get(2));if(info==null)throw new FileNotFoundException("附件不属于此任务");File file=HandoffFiles.regular(HandoffFiles.staging(context.getFilesDir(),parts.get(0),false),parts.get(2)+".bin",HandoffRequest.MAX_FILE);if(file.length()!=info.metadata.getLong("size"))throw new FileNotFoundException("附件已改变");return info;
    }
    static synchronized void revoke(String id){GRANTS.remove(id);}
}
