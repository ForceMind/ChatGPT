package com.forcemind.chatgpt.profiles;

import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import org.json.JSONObject;

/** All native history, draft and text preview bytes are authenticated and encrypted. */
final class EnvironmentRecoveryStore {
    private final File files,root;
    private final EncryptedPayloads cipher;
    EnvironmentRecoveryStore(Context context)throws Exception{
        files=context.getFilesDir().getCanonicalFile();root=new File(files,"environment-recovery");
        HandoffFiles.directory(root,true);cipher=DevicePayloadKeys.recoveryCipher(context);
    }
    private void owner(String account,String storage,String tab,String url)throws Exception{
        WorkspaceState.profile(account);WorkspaceState.profile(storage);WorkspaceState.profile(tab);
        WorkspaceState.Account a=new StateStore(files).load().find(account);
        if(!a.isEnvironment()||!storage.equals(a.storageId)||a.tabs.stream().noneMatch(t->t.id.equals(tab)&&t.url.equals(url)))throw new IOException("标签或登录空间已改变");
        if(EnvironmentUrls.recoverable(url)==null)throw new IOException("此页面不保存恢复记录");
    }
    private File directory(String account,String storage,boolean create)throws Exception{
        File a=new File(root,account),s=new File(a,storage);HandoffFiles.directory(root,false);HandoffFiles.directory(a,create);HandoffFiles.directory(s,create);return s;
    }
    private JSONObject normalize(JSONObject raw,String account,String storage,String tab,String url)throws Exception{
        if(raw.getInt("schema")!=1||!account.equals(raw.getString("accountId"))||!storage.equals(raw.getString("storageId"))||!tab.equals(raw.getString("tabId"))||!url.equals(raw.getString("url")))throw new IOException("恢复记录归属无效");
        if(raw.toString().length()>900000||raw.optString("nativeSessionState").length()>700000)throw new IOException("恢复记录过大");
        JSONObject document=raw.optJSONObject("document");
        if(document!=null){if(!url.equals(document.getString("url"))||document.getString("draft").length()>100000||document.getString("preview").length()>100000)throw new IOException("文档恢复记录无效");double scroll=document.getDouble("scroll"),ratio=document.getDouble("ratio");if(!Double.isFinite(scroll)||scroll<0||scroll>100000000||!Double.isFinite(ratio)||ratio<0||ratio>1)throw new IOException("阅读位置无效");}
        return raw;
    }
    private JSONObject decode(File dir,String name,String account,String storage,String tab,String url)throws Exception{
        byte[] bytes=Files.readAllBytes(HandoffFiles.regular(dir,name,1024*1024+32).toPath());
        JSONObject raw=new JSONObject(cipher.open(tab,bytes));String savedUrl=raw.getString("url");if(!savedUrl.equals(EnvironmentUrls.recoverable(savedUrl)))throw new IOException("恢复地址无效");return normalize(raw,account,storage,tab,savedUrl);
    }
    synchronized JSONObject load(String account,String storage,String tab,String url)throws Exception{
        try(FileChannel lock=FileChannel.open(new File(root,".recovery.lock").toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=lock.lock()){return loadLocked(account,storage,tab,url);}
    }
    private JSONObject loadLocked(String account,String storage,String tab,String url)throws Exception{
        owner(account,storage,tab,url);File a=new File(root,account),s=new File(a,storage);if(!a.exists()||!s.exists())return null;
        File dir=directory(account,storage,false);String name=tab+".bin";Exception rejected=null;
        if(new File(dir,name).exists())try{JSONObject value=decode(dir,name,account,storage,tab,url);return url.equals(value.getString("url"))?value:null;}catch(Exception e){rejected=e;}
        if(new File(dir,name+".bak").exists())try{JSONObject value=decode(dir,name+".bak",account,storage,tab,url);return url.equals(value.getString("url"))?value:null;}catch(Exception e){if(rejected==null)rejected=e;}
        if(rejected!=null)throw new IOException("本地恢复记录不可读取，已保留原件",rejected);return null;
    }
    synchronized void save(JSONObject raw)throws Exception{
        try(FileChannel lock=FileChannel.open(new File(root,".recovery.lock").toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=lock.lock()){saveLocked(raw);}
    }
    private void saveLocked(JSONObject raw)throws Exception{
        String account=raw.getString("accountId"),storage=raw.getString("storageId"),tab=raw.getString("tabId"),url=raw.getString("url");
        owner(account,storage,tab,url);normalize(raw,account,storage,tab,url);byte[] encrypted=cipher.seal(tab,raw.toString());
        owner(account,storage,tab,url);File dir=directory(account,storage,true),target=new File(dir,tab+".bin");
        if(target.exists()){
            // Preserve a previous valid encrypted record even if it describes an earlier URL.
            byte[] previous=Files.readAllBytes(HandoffFiles.regular(dir,target.getName(),1024*1024+32).toPath());
            cipher.open(tab,previous);atomic(new File(dir,target.getName()+".bak"),previous);
        }
        atomic(target,encrypted);
    }
    private void atomic(File target,byte[] bytes)throws Exception{
        File dir=target.getParentFile(),next=new File(dir,target.getName()+".new");
        if(target.exists())HandoffFiles.regular(dir,target.getName(),1024*1024+32);
        if(next.exists())HandoffFiles.regular(dir,next.getName(),1024*1024+32);
        try(FileChannel out=FileChannel.open(next.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);}
        Files.move(next.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
