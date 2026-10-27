package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Encrypted bounded records with original-preserving recovery and epoch cancellation. */
final class PageRecoveryStore {
    private static final Object LOCK=new Object();
    private static final Map<String,Long> GENERATIONS=new HashMap<>();
    private final File files,root;
    private final EncryptedPayloads cipher;
    private final Map<String,String> fingerprints=new HashMap<>();
    PageRecoveryStore(File files,EncryptedPayloads cipher)throws Exception{this.files=files.getCanonicalFile();this.cipher=cipher;root=new File(this.files,"page-recovery");HandoffFiles.directory(root,true);}
    private String key(String account,String storage){return root+"/"+account+"/"+storage;}
    long generation(String account,String storage){synchronized(LOCK){return GENERATIONS.getOrDefault(key(account,storage),0L);}}
    private File directory(String account,String storage,boolean create)throws Exception{
        WorkspaceState.profile(account);WorkspaceState.profile(storage);HandoffFiles.directory(root,false);File a=new File(root,account);HandoffFiles.directory(a,create);File s=new File(a,storage);HandoffFiles.directory(s,create);return s;
    }
    private JSONObject decode(File dir,String name,String account,String storage,String tab)throws Exception{
        byte[] bytes=Files.readAllBytes(HandoffFiles.regular(dir,name,1024*1024+32).toPath());JSONObject record=PageRecoveryRecord.normalize(new JSONObject(cipher.open(tab,bytes)));
        if(!record.getString("accountId").equals(account)||!record.getString("storageId").equals(storage)||!record.getString("tabId").equals(tab))throw new IOException("恢复记录不属于此账号标签");return record;
    }
    JSONObject load(String account,String storage,String tab,String url)throws Exception{
        synchronized(LOCK){WorkspaceState.profile(tab);PageRecoveryRecord.available(files,account,storage,tab);if(url==null)return null;File a=new File(root,account),s=new File(a,storage);if(!a.exists()||!s.exists())return null;File dir=directory(account,storage,false);String name=tab+".bin";JSONObject value=null;Exception original=null;
            if(new File(dir,name).exists())try{value=decode(dir,name,account,storage,tab);}catch(Exception e){original=e;}
            if(value==null&&new File(dir,name+".bak").exists()){
                value=decode(dir,name+".bak",account,storage,tab);
                if(original!=null){File bad=HandoffFiles.regular(dir,name,1024*1024+32);Files.copy(bad.toPath(),new File(dir,tab+".corrupt-"+System.currentTimeMillis()+".bin").toPath());}
                write(new File(dir,name),Files.readAllBytes(HandoffFiles.regular(dir,name+".bak",1024*1024+32).toPath()));
            }
            if(value==null&&original!=null)throw new IOException("本地恢复记录损坏，原文件已保留",original);
            if(value!=null)fingerprints.put(key(account,storage)+"/"+tab,fingerprint(value));
            return value!=null&&value.getString("url").equals(url)?value:null;
        }
    }
    boolean save(JSONObject raw,long generation)throws Exception{
        JSONObject record=PageRecoveryRecord.normalize(raw);String account=record.getString("accountId"),storage=record.getString("storageId"),tab=record.getString("tabId");
        synchronized(LOCK){if(generation(account,storage)!=generation)return false;PageRecoveryRecord.available(files,account,storage,tab);String text=record.toString(),fingerprint=fingerprint(record);String id=key(account,storage)+"/"+tab;if(fingerprint.equals(fingerprints.get(id)))return false;
            byte[] encrypted=cipher.seal(tab,text);PageRecoveryRecord.available(files,account,storage,tab);if(generation(account,storage)!=generation)return false;
            File dir=directory(account,storage,true),file=new File(dir,tab+".bin");if(file.exists()){decode(dir,file.getName(),account,storage,tab);write(new File(dir,file.getName()+".bak"),Files.readAllBytes(file.toPath()));}write(file,encrypted);fingerprints.put(id,fingerprint);
            return true;}
    }
    private String fingerprint(JSONObject record)throws Exception{
        JSONObject value=new JSONObject(record.toString()).put("capturedAt",0),page=value.optJSONObject("page");
        if(page!=null){page.put("capturedAt",0);if(page.has("imageCapturedAt"))page.put("imageCapturedAt",0);}
        return HandoffRequest.hash(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void write(File target,byte[] bytes)throws Exception{
        File parent=target.getParentFile();HandoffFiles.directory(parent,false);File next=new File(parent,target.getName()+".new");
        if(target.exists())HandoffFiles.regular(parent,target.getName(),1024*1024+32);if(next.exists())HandoffFiles.regular(parent,next.getName(),1024*1024+32);
        try(java.nio.channels.FileChannel out=java.nio.channels.FileChannel.open(next.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}
        Files.move(next.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    void clear(String account,String storage)throws Exception{
        synchronized(LOCK){String prefix=key(account,storage);GENERATIONS.put(prefix,generation(account,storage)+1);fingerprints.keySet().removeIf(k->k.startsWith(prefix+"/"));File a=new File(root,account),s=new File(a,storage);if(!a.exists()||!s.exists())return;File dir=directory(account,storage,false);File[] entries=dir.listFiles();if(entries==null)throw new IOException("无法清理恢复目录");for(File f:entries)if(f.getName().matches("[a-f0-9-]{36}(?:\\.corrupt-[0-9]+)?\\.bin(?:\\.bak|\\.new)?")){HandoffFiles.regular(dir,f.getName(),1024*1024+32);Files.delete(f.toPath());}if(dir.list().length==0)Files.delete(dir.toPath());}
    }
    void forget(String account,String storage,String tab)throws Exception{
        synchronized(LOCK){WorkspaceState.profile(tab);File a=new File(root,account),s=new File(a,storage);if(!s.exists())return;File dir=directory(account,storage,false);for(String suffix:new String[]{".bin",".bin.bak",".bin.new"}){File f=new File(dir,tab+suffix);if(f.exists())Files.delete(HandoffFiles.regular(dir,f.getName(),1024*1024+32).toPath());}fingerprints.remove(key(account,storage)+"/"+tab);}
    }
}
