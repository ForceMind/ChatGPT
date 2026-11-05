package com.forcemind.chatgpt.profiles;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class StateStore {
    // Android processes each own a JVM lock; the file lock coordinates between them.
    private static final Object PROCESS_LOCK = new Object();
    private final AtomicFile file;
    private final File backup, lockFile;
    private final AtomicFile revoked, environments;
    StateStore(File directory) {
        file = new AtomicFile(new File(directory,"workspace.json")); backup = new File(directory,"workspace.backup.json");
        lockFile = new File(directory,"workspace.lock"); revoked = new AtomicFile(new File(directory,"revoked-profiles.json"));
        environments = new AtomicFile(new File(directory,"environment-groups.json"));
    }
    private interface Locked<T> { T run() throws Exception; }
    private <T> T locked(Locked<T> action) throws Exception {
        synchronized(PROCESS_LOCK) {
            File directory=lockFile.getParentFile();
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建账号记录目录");
            try(RandomAccessFile handle=new RandomAccessFile(lockFile,"rw");FileLock ignored=handle.getChannel().lock()){return action.run();}
        }
    }
    WorkspaceState load() throws Exception { return locked(this::loadLocked); }
    WorkspaceState update(Consumer<WorkspaceState> mutation) throws Exception {
        return locked(()->{
            WorkspaceState state=loadLocked();mutation.accept(state);
            byte[] next=state.json().toString().getBytes(StandardCharsets.UTF_8);
            if(!file.getBaseFile().exists()||!java.util.Arrays.equals(file.readFully(),next))saveLocked(state);
            return state;
        });
    }
    void save(WorkspaceState state) throws Exception { locked(()->{saveLocked(state);return null;}); }
    private WorkspaceState loadLocked() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile()+".bak").exists()) {
            if(backupExists())return protect(readBackup());
            WorkspaceState fresh=protect(WorkspaceState.fresh());saveLocked(fresh);return fresh;
        }
        byte[] original=file.readFully();
        try{return protect(WorkspaceState.parse(new String(original,StandardCharsets.UTF_8)));}
        catch(Exception e){
            if(!backupExists())throw new IOException("账号记录损坏，已停止启动以保护登录空间。请保留应用数据并反馈。",e);
            WorkspaceState recovered=protect(readBackup());
            Files.write(new File(file.getBaseFile().getParent(),"workspace.corrupt-"+System.currentTimeMillis()+".json").toPath(),original);
            write(file,recovered.json().toString().getBytes(StandardCharsets.UTF_8));return recovered;
        }
    }
    private boolean backupExists(){return backup.exists()||new File(backup+".bak").exists();}
    private WorkspaceState readBackup()throws Exception{return WorkspaceState.parse(new String(new AtomicFile(backup).readFully(),StandardCharsets.UTF_8));}
    private static final class Revocation {
        final String storageId, language, timeZone;final int slot;
        Revocation(String storageId,int slot,String language,String timeZone){this.storageId=storageId;this.slot=slot;this.language=language;this.timeZone=timeZone;}
    }
    private Map<String,Revocation> revocations()throws Exception {
        Map<String,Revocation> records=new LinkedHashMap<>();
        if(revoked.getBaseFile().exists()||new File(revoked.getBaseFile()+".bak").exists()){
            JSONArray list=new JSONArray(new String(revoked.readFully(),StandardCharsets.UTF_8));
            for(int i=0;i<list.length();i++){
                Object raw=list.get(i);Revocation entry;
                if(raw instanceof String)entry=new Revocation((String)raw,-1,null,null);
                else{
                    JSONObject value=list.getJSONObject(i);int slot=value.getInt("environmentSlot");EnvironmentConfig.validateSlot(slot);
                    String language=EnvironmentConfig.canonicalLanguage(value.getString("language")),zone=EnvironmentConfig.canonicalTimeZone(value.getString("timeZone"));
                    entry=new Revocation(value.getString("storageId"),slot,language,zone);
                }
                WorkspaceState.profile(entry.storageId);records.put(entry.storageId,entry);
            }
        }
        return records;
    }
    void revoke(String storageId)throws Exception{
        locked(()->{
            WorkspaceState.profile(storageId);Map<String,Revocation> records=revocations();
            if(!records.containsKey(storageId)){
                WorkspaceState state=loadLocked();WorkspaceState.Account account=null;
                for(WorkspaceState.Account a:state.accounts)if(a.storageId.equals(storageId)){account=a;break;}
                records.put(storageId,account!=null&&account.isEnvironment()?new Revocation(storageId,account.environmentSlot,account.language,account.timeZone):new Revocation(storageId,-1,null,null));
            }
            JSONArray list=new JSONArray();for(Revocation entry:records.values()){
                if(entry.slot<0)list.put(entry.storageId);
                else list.put(new JSONObject().put("storageId",entry.storageId).put("environmentSlot",entry.slot).put("language",entry.language).put("timeZone",entry.timeZone));
            }
            write(revoked,list.toString().getBytes(StandardCharsets.UTF_8));return null;
        });
    }
    private void reconcileGroups(WorkspaceState state)throws Exception{
        if(!environments.getBaseFile().exists()&&!new File(environments.getBaseFile()+".bak").exists())return;
        JSONArray records=new JSONArray(new String(environments.readFully(),StandardCharsets.UTF_8));
        if(records.length()>EnvironmentConfig.MAX_SLOTS)throw new IOException("环境槽位记录损坏");
        for(int i=0;i<records.length();i++){
            JSONObject value=records.getJSONObject(i);Object number=value.get("slot");
            if(!(number instanceof Number)||((Number)number).doubleValue()!=((Number)number).intValue())throw new IOException("环境槽位记录损坏");
            int slot=((Number)number).intValue();EnvironmentConfig.validateSlot(slot);
            String language=EnvironmentConfig.canonicalLanguage(value.getString("language")),zone=EnvironmentConfig.canonicalTimeZone(value.getString("timeZone"));
            WorkspaceState.EnvironmentGroup match=null;for(WorkspaceState.EnvironmentGroup group:state.environmentGroups)if(group.slot==slot)match=group;
            if(match==null)state.environmentGroups.add(new WorkspaceState.EnvironmentGroup(slot,language,zone));
            else if(!match.language.equals(language)||!match.timeZone.equals(zone))throw new IOException("环境槽位不可重新分配");
        }
    }
    private WorkspaceState protect(WorkspaceState state)throws Exception{
        reconcileGroups(state);
        for(Revocation entry:revocations().values()){
            if(entry.slot>=0){
                WorkspaceState.EnvironmentGroup found=null;
                for(WorkspaceState.EnvironmentGroup group:state.environmentGroups)if(group.slot==entry.slot)found=group;
                if(found==null)state.environmentGroups.add(new WorkspaceState.EnvironmentGroup(entry.slot,entry.language,entry.timeZone));
                else if(!found.language.equals(entry.language)||!found.timeZone.equals(entry.timeZone))throw new IOException("环境记录不一致，已停止恢复以保护登录空间");
            }
            for(WorkspaceState.Account a:state.accounts)if(a.storageId.equals(entry.storageId))state.clearAccount(a);
            if(entry.slot>=0){
                if(state.pendingEnvironmentDeletes.stream().noneMatch(d->d.storageId.equals(entry.storageId)))state.pendingEnvironmentDeletes.add(new WorkspaceState.EnvironmentDelete(entry.storageId,entry.slot));
            }else if(!state.pendingDeletes.contains(entry.storageId))state.pendingDeletes.add(entry.storageId);
        }
        state.validate();return state;
    }
    private void saveLocked(WorkspaceState state)throws Exception{
        Map<String,Revocation> records=revocations();
        for(WorkspaceState.Account account:state.accounts)if(records.containsKey(account.storageId))throw new IOException("已撤销的空间不可重新启用");
        // Reserve groups before workspace commit so backup recovery can never recycle a live process slot.
        reconcileGroups(state);
        byte[] bytes=state.json().toString().getBytes(StandardCharsets.UTF_8);
        if(file.getBaseFile().exists()){
            byte[] previous=file.readFully();WorkspaceState before=WorkspaceState.parse(new String(previous,StandardCharsets.UTF_8));
            ensureStableEnvironments(before,state);write(new AtomicFile(backup),previous);
        }
        if(!state.environmentGroups.isEmpty()){
            JSONArray groups=new JSONArray();for(WorkspaceState.EnvironmentGroup group:state.environmentGroups)groups.put(group.json());
            write(environments,groups.toString().getBytes(StandardCharsets.UTF_8));
        }
        write(file,bytes);
    }
    static void ensureStableEnvironments(WorkspaceState before,WorkspaceState after)throws IOException{
        for(WorkspaceState.EnvironmentGroup group:before.environmentGroups){
            boolean same=false;for(WorkspaceState.EnvironmentGroup next:after.environmentGroups)if(next.slot==group.slot&&next.language.equals(group.language)&&next.timeZone.equals(group.timeZone))same=true;
            if(!same)throw new IOException("已创建的环境不能更改或重新分配");
        }
        for(WorkspaceState.Account old:before.accounts)for(WorkspaceState.Account next:after.accounts)if(old.id.equals(next.id)){
            if(!old.engine.equals(next.engine)||old.isEnvironment()&&(!old.language.equals(next.language)||!old.timeZone.equals(next.timeZone)||old.environmentSlot!=next.environmentSlot))throw new IOException("账号环境创建后不可更改，请创建新账号空间");
        }
    }
    private static void write(AtomicFile file,byte[] data)throws IOException{
        FileOutputStream stream=file.startWrite();
        try{stream.write(data);file.finishWrite(stream);}catch(Exception e){file.failWrite(stream);throw e;}
    }
}
