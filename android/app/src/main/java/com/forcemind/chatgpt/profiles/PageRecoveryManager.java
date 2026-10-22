package com.forcemind.chatgpt.profiles;

import android.os.*;
import android.webkit.WebView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

/** Native-to-page snapshots only. No JavaScript-to-native bridge or background polling. */
final class PageRecoveryManager {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final MainActivity activity;
    final PageRecoveryStore store;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<WebView,Session> sessions=new IdentityHashMap<>();
    private final Set<String> notices=new HashSet<>();
    private final String script;
    private boolean foreground,closed;
    private static final class Session {
        final WebView web;final String account,storage,tab,key,token;final long generation;
        long serial,deadline,captureTicket;String url,alternative="";boolean installed,loading,restoring,restoreCall,inflight,blocked;JSONObject saved;
        Session(WebView web,String account,String storage,String tab,long generation){this.web=web;this.account=account;this.storage=storage;this.tab=tab;this.generation=generation;token=WorkspaceState.id();key="__profiles_recovery_"+token.replace("-","");}
    }
    PageRecoveryManager(MainActivity activity)throws Exception{
        this.activity=activity;store=new PageRecoveryStore(activity.getFilesDir(),DevicePayloadKeys.recoveryCipher(activity.getApplicationContext()));
        try(InputStream in=activity.getAssets().open("page-recovery.js")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);script=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
    }
    void bind(WebView web,String account,String storage,String tab){sessions.put(web,new Session(web,account,storage,tab,store.generation(account,storage)));}
    private boolean valid(Session s){return !closed&&sessions.get(s.web)==s&&activity.recoveryValid(s.account,s.storage,s.tab,s.web)&&store.generation(s.account,s.storage)==s.generation;}
    private boolean visible(Session s){return foreground&&activity.recoveryCurrentWeb()==s.web&&s.web.isShown();}
    void navigating(WebView web){Session s=sessions.get(web);if(s!=null){s.serial++;s.installed=false;s.loading=false;s.restoring=false;s.restoreCall=false;s.inflight=false;s.saved=null;}}
    void ready(WebView web){
        Session s=sessions.get(web);if(s==null||!valid(s)||web.getProgress()<100)return;String url=PageRecoveryRecord.url(web.getUrl());
        if(url==null){navigating(web);return;}if(s.installed&&url.equals(s.url)){visible();return;}
        s.serial++;s.url=url;s.installed=false;s.loading=true;s.restoring=false;s.restoreCall=false;s.inflight=false;s.saved=null;long serial=s.serial;
        try{
            JSONObject config=new JSONObject().put("key",s.key).put("token",s.token);
            web.evaluateJavascript(script.replace("__CONFIG__",config.toString()),raw->{if(!valid(s)||s.serial!=serial)return;try{
                JSONObject result=parsed(raw);if(!result.optString("status").equals("installed"))throw new IOException();s.installed=true;
                IO.execute(()->{try{JSONObject record=store.load(s.account,s.storage,s.tab,url);handler.post(()->{if(!valid(s)||s.serial!=serial)return;s.loading=false;s.saved=record;if(record!=null){s.alternative=record.optString("alternativeDraft",s.alternative);s.restoring=true;s.deadline=0;restore(s);}else finish(s);});}
                    catch(Exception e){handler.post(()->{if(!valid(s)||s.serial!=serial)return;s.loading=false;s.blocked=true;notice("恢复记录暂不可读，已保留原文件；网页可继续使用。可从账号菜单查看本地草稿。");});}});
            }catch(Exception e){s.loading=false;notice("此页面暂不支持草稿恢复，网页可继续使用。");}});
        }catch(Exception e){s.loading=false;notice("无法准备页面恢复组件，网页可继续使用。");}
    }
    private JSONObject parsed(String raw)throws Exception{if(raw==null||raw.length()>1400000)throw new IOException();Object value=new JSONTokener(raw).nextValue();return value instanceof JSONObject?(JSONObject)value:new JSONObject(String.valueOf(value));}
    private void evaluate(Session s,String method,String argument,Consumer<JSONObject> callback){
        if(!valid(s)||!s.installed||!Objects.equals(s.url,PageRecoveryRecord.url(s.web.getUrl())))return;long serial=s.serial;
        String js="(function(){try{var a=window["+JSONObject.quote(s.key)+"];if(window!==top||!a||a.token!=="+JSONObject.quote(s.token)+")return JSON.stringify({status:'skip'});return JSON.stringify(a."+method+"("+argument+"));}catch(e){return JSON.stringify({status:'skip'});}})()";
        s.web.evaluateJavascript(js,raw->{if(!valid(s)||s.serial!=serial||!Objects.equals(s.url,PageRecoveryRecord.url(s.web.getUrl())))return;try{callback.accept(parsed(raw));}catch(Exception e){notice("页面恢复检查未完成，已保留本地记录。");}});
    }
    private void restore(Session s){
        if(!valid(s)||!s.restoring||s.restoreCall||!visible(s))return;s.restoreCall=true;if(s.deadline==0)s.deadline=SystemClock.elapsedRealtime()+15000;
        boolean force=SystemClock.elapsedRealtime()>=s.deadline;String args=s.saved.toString()+","+force;
        evaluate(s,"restore",args,result->{String status=result.optString("status");
            if(status.equals("waiting")&&!force){s.restoreCall=false;handler.postDelayed(()->restore(s),300);return;}
            if(status.equals("restored")||status.equals("already-sent")){finish(s);return;}preserve(s);
        });
    }
    private void preserve(Session s){if(s.saved!=null&&!s.saved.optString("draft").isEmpty()){s.alternative=s.saved.optString("draft");notice("本地草稿已保留，可从账号菜单的“本地恢复草稿”查看；不会覆盖网页现有内容。");}finish(s);}
    private void finish(Session s){s.restoring=false;s.restoreCall=false;s.deadline=0;evaluate(s,"finish","",ignored->{});}
    void visible(){WebView web=activity.recoveryCurrentWeb();Session s=sessions.get(web);if(s!=null&&s.restoring)restore(s);}
    void checkpoint(){WebView web=activity.recoveryCurrentWeb();Session s=sessions.get(web);if(s!=null)capture(s,true);}
    void checkpointVisible(){for(Session s:new ArrayList<>(sessions.values()))if(s.web.getParent()!=null&&s.web.isShown())capture(s,true);}
    private void capture(Session s,boolean force){
        if(!valid(s)||!s.installed||s.loading||s.restoring||s.inflight||s.blocked)return;s.inflight=true;long ticket=++s.captureTicket,serial=s.serial;
        handler.postDelayed(()->{if(valid(s)&&s.serial==serial&&s.inflight&&s.captureTicket==ticket){s.inflight=false;s.captureTicket++;notice("网页繁忙，最近的修改尚未保存，请稍候或手动备份。");}},2000);
        evaluate(s,"capture",String.valueOf(force),value->{if(s.captureTicket!=ticket)return;s.inflight=false;String status=value.optString("status");if(status.equals("oversize")){notice("草稿超过 10 万字符，本地未保存，请手动备份。");return;}if(!status.equals("captured"))return;
            try{if(!Objects.equals(s.url,PageRecoveryRecord.url(value.getString("url"))))return;JSONObject record=new JSONObject().put("schema",1).put("accountId",s.account).put("storageId",s.storage).put("tabId",s.tab).put("url",s.url).put("draft",value.getString("draft")).put("scroll",value.getDouble("scroll")).put("ratio",value.getDouble("ratio")).put("capturedAt",System.currentTimeMillis());
                if(value.has("anchor"))record.put("anchor",value.getJSONObject("anchor"));if(!s.alternative.isEmpty()&&!s.alternative.equals(record.getString("draft")))record.put("alternativeDraft",s.alternative);PageRecoveryRecord.normalize(record);
                IO.execute(()->{try{store.save(record,s.generation);}catch(Exception e){handler.post(()->notice("本地草稿保存未完成，请手动备份；不会写入未加密内容。"));}});
            }catch(Exception e){notice("页面草稿或阅读位置无效，本次未保存。");}
        });
    }
    private final Runnable poll=new Runnable(){public void run(){if(closed||!foreground)return;Session s=sessions.get(activity.recoveryCurrentWeb());if(s!=null&&visible(s))capture(s,false);handler.postDelayed(this,4000);}};
    void resume(){foreground=true;handler.removeCallbacks(poll);handler.post(poll);visible();}
    void pause(){checkpoint();foreground=false;handler.removeCallbacks(poll);for(Session s:sessions.values())if(s.restoring)preserve(s);}
    void unbind(WebView web){Session s=sessions.remove(web);if(s!=null)s.serial++;}
    void clear(String account,String storage)throws Exception{store.clear(account,storage);for(Session s:sessions.values())if(s.account.equals(account)&&s.storage.equals(storage)){s.serial++;s.blocked=true;}}
    void forget(String account,String storage,String tab){IO.execute(()->{try{store.forget(account,storage,tab);}catch(Exception e){handler.post(()->notice("旧标签恢复记录未能清理，文件已保留。"));}});}
    void localDraft(Consumer<JSONObject> done){
        Session s=sessions.get(activity.recoveryCurrentWeb());if(s==null){done.accept(null);return;}String url=PageRecoveryRecord.url(s.web.getUrl());IO.execute(()->{try{JSONObject record=store.load(s.account,s.storage,s.tab,url);handler.post(()->{if(valid(s))done.accept(record);});}catch(Exception e){handler.post(()->notice("本地恢复记录暂不可读，请保留应用数据并反馈。"));}});
    }
    void close(){closed=true;foreground=false;handler.removeCallbacksAndMessages(null);sessions.clear();}
    private void notice(String message){if(!closed&&notices.add(message))activity.recoveryNotice(message);}
}
