package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONArray;
import org.json.JSONObject;

/** DUMP-protected CI control only. Never loads a page or injects a test marker. */
public final class EnvironmentAcceptanceActivity extends Activity {
    private static final String MANIFEST="environment-acceptance.json";
    private EnvironmentNetworkControl networkControl;
    private String controlRequestId,controlAction,controlAccount,controlErrorClass;
    private Integer controlTab;
    private boolean controlCompleted,controlSucceeded;
    @Override public void onCreate(Bundle saved){
        if(!BuildConfig.ENVIRONMENT_TEST)throw new SecurityException("Environment acceptance disabled");
        super.onCreate(saved);dispatch(getIntent());
    }
    @Override public void onNewIntent(Intent intent){
        if(!BuildConfig.ENVIRONMENT_TEST)throw new SecurityException("Environment acceptance disabled");
        super.onNewIntent(intent);setIntent(intent);dispatch(intent);
    }
    private void dispatch(Intent intent){
        if(!BuildConfig.ENVIRONMENT_TEST||!BuildConfig.ENVIRONMENT_CLIENT)throw new SecurityException("Environment acceptance disabled");
        try{
            if(intent==null)throw new IllegalArgumentException("Missing explicit action");String action=intent.getStringExtra("action");
            if(action!=null&&action.startsWith("network-")){if(!BuildConfig.ENVIRONMENT_NETWORK_TEST)throw new SecurityException("Network fixture disabled");networkControl=new EnvironmentNetworkControl(this);networkControl.run(intent);return;}
            if(!"prepare".equals(action)&&!"open".equals(action)&&!"record".equals(action))throw new IllegalArgumentException("Unknown action");
            Bundle extras=intent.getExtras();if(extras!=null)for(String key:extras.keySet())if(!"action".equals(key)&&!("requestId".equals(key)&&"com.forcemind.chatgpt.profiles".equals(getPackageName()))&&(!("open".equals(action)||"record".equals(action))||!"account".equals(key)&&!"tab".equals(key)))throw new IllegalArgumentException("Unknown input");
            bindControlRequest(intent,action);
            if("prepare".equals(action))prepare();else if("record".equals(action))record(intent);else open(intent);
            if(controlRequestId!=null){controlSucceeded=true;controlCompleted=true;}
            setResult(RESULT_OK);finish();
        }catch(Exception error){
            if(controlRequestId!=null&&intent!=null&&controlRequestId.equals(intent.getStringExtra("requestId"))){controlSucceeded=false;controlCompleted=true;controlErrorClass=error.getClass().getName();}
            if(intent!=null&&"record".equals(intent.getStringExtra("action"))){
                try{writeProbe(new JSONObject().put("schema",1).put("complete",false).put("errorClass",error.getClass().getName()).put("sampledAt",System.currentTimeMillis()));}catch(Exception writeError){android.util.Log.e("EnvironmentAcceptance","Probe output failed: "+writeError.getClass().getName());}
            }else android.util.Log.e("EnvironmentAcceptance","Acceptance command rejected",error);
            setResult(RESULT_CANCELED);finish();
        }
    }
    private void bindControlRequest(Intent intent,String action)throws Exception{
        if(!intent.hasExtra("requestId"))return;
        if(!BuildConfig.ENVIRONMENT_TEST||!BuildConfig.ENVIRONMENT_CLIENT||!"com.forcemind.chatgpt.profiles".equals(getPackageName()))throw new SecurityException("Regular CI control requests only");
        Object raw=intent.getExtras().get("requestId");
        if(!(raw instanceof String)||!((String)raw).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("Invalid control request ID");
        String requestId=(String)raw;
        if(controlRequestId!=null)throw new IllegalStateException("Control request already bound");
        File target=new File(getFilesDir(),"environment-control-"+requestId+".json");
        if(target.exists()||new File(target.getPath()+".new").exists())throw new IllegalStateException("Control request already used");
        controlRequestId=requestId;controlAction=action;
        controlAccount="prepare".equals(action)?null:intent.getStringExtra("account");
        controlTab="prepare".equals(action)?null:Integer.valueOf(intent.getIntExtra("tab",-1));
    }
    private File manifest(){return new File(getFilesDir(),MANIFEST);}
    private static String origin(){
        URI home=URI.create(BuildConfig.ENVIRONMENT_HOME_URL);
        String host=BuildConfig.ENVIRONMENT_NETWORK_TEST?"network-fixture.test":"environment-fixture.test";
        if(!"https".equals(home.getScheme())||!host.equals(home.getHost())||home.getPort()!=18480||home.getRawUserInfo()!=null||home.getRawQuery()!=null||home.getRawFragment()!=null)throw new SecurityException("Unexpected acceptance origin");
        return home.getScheme()+"://"+home.getHost()+":"+home.getPort();
    }
    private void prepare()throws Exception{
        if(manifest().exists())throw new IllegalStateException("Acceptance already prepared");
        String base=origin();JSONObject output=new JSONObject().put("schema",1);JSONObject accounts=new JSONObject();JSONArray legacy=new JSONArray();
        new StateStore(getFilesDir()).update(state->{
            if(manifest().exists())throw new IllegalStateException("Acceptance already prepared");
            try{
                for(WorkspaceState.Account old:state.accounts)if(!old.isEnvironment())legacy.put(new JSONObject().put("id",old.id).put("storageId",old.storageId));
                if(legacy.length()==0)throw new IllegalStateException("An existing legacy account must be preserved");
                if(state.accounts.size()>WorkspaceState.MAX_ACCOUNTS-3)throw new IllegalStateException("Not enough account slots");
                WorkspaceState.Account a=state.addEnvironmentAccount("环境 A","",WorkspaceState.COLORS[1],"zh-CN","Asia/Shanghai");state.addTab(a);a.tabs.get(0).url=base+"/c/a1";a.tabs.get(1).url=base+"/c/a2";a.activeTab=a.tabs.get(0).id;
                WorkspaceState.Account b=state.addEnvironmentAccount("环境 B","",WorkspaceState.COLORS[2],"en-US","America/New_York");b.tabs.get(0).url=base+"/c/b1";
                WorkspaceState.Account c=state.addEnvironmentAccount("环境 C","",WorkspaceState.COLORS[3],"zh-CN","Asia/Shanghai");c.tabs.get(0).url=base+"/c/c1";
                accounts.put("A",account(a)).put("B",account(b)).put("C",account(c));state.activeAccount=a.id;
            }catch(org.json.JSONException e){throw new IllegalStateException(e);}
        });
        output.put("accounts",accounts).put("legacyAccounts",legacy);
        File temporary=new File(getFilesDir(),MANIFEST+".new");
        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(temporary)){stream.write(output.toString().getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}
        Files.move(temporary.toPath(),manifest().toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    private static JSONObject account(WorkspaceState.Account account)throws org.json.JSONException{
        JSONArray tabs=new JSONArray();for(WorkspaceState.Tab tab:account.tabs)tabs.put(new JSONObject().put("id",tab.id).put("url",tab.url));
        return new JSONObject().put("id",account.id).put("storageId",account.storageId).put("environmentSlot",account.environmentSlot).put("language",account.language).put("timeZone",account.timeZone).put("tabs",tabs);
    }
    private void open(Intent intent)throws Exception{
        String label=intent.getStringExtra("account");if(!"A".equals(label)&&!"B".equals(label)&&!"C".equals(label))throw new IllegalArgumentException("Unknown account");
        Object input=intent.getExtras().get("tab");if(!(input instanceof Integer))throw new IllegalArgumentException("Tab must be an integer");int index=(Integer)input;
        if(index<0||index>("A".equals(label)?1:0))throw new IllegalArgumentException("Unknown tab");
        if(!manifest().isFile()||manifest().length()>65536)throw new IllegalStateException("Acceptance manifest missing or invalid");
        JSONObject root=new JSONObject(new String(Files.readAllBytes(manifest().toPath()),StandardCharsets.UTF_8));if(root.getInt("schema")!=1)throw new IllegalStateException("Unsupported manifest");
        JSONObject expected=root.getJSONObject("accounts").getJSONObject(label);String id=expected.getString("id"),storage=expected.getString("storageId"),tabId=expected.getJSONArray("tabs").getJSONObject(index).getString("id");WorkspaceState.profile(id);WorkspaceState.profile(storage);WorkspaceState.profile(tabId);
        new StateStore(getFilesDir()).update(state->{
            WorkspaceState.Account account=state.find(id);if(!account.isEnvironment()||!account.storageId.equals(storage)||account.tabs.stream().noneMatch(tab->tab.id.equals(tabId)))throw new IllegalStateException("Account changed since prepare");
            state.activeAccount=id;account.activeTab=tabId;
        });
        EnvironmentLauncher.open(this,id);
    }
    private void record(Intent intent)throws Exception{
        String label=intent.getStringExtra("account");if(!"A".equals(label)&&!"B".equals(label)&&!"C".equals(label))throw new IllegalArgumentException("Unknown account");
        Object input=intent.getExtras().get("tab");if(!(input instanceof Integer))throw new IllegalArgumentException("Tab must be an integer");int index=(Integer)input;
        if(index<0||index>("A".equals(label)?1:0))throw new IllegalArgumentException("Unknown tab");
        if(!manifest().isFile()||manifest().length()>65536)throw new IllegalStateException("Acceptance manifest missing or invalid");
        JSONObject manifestRoot=new JSONObject(new String(Files.readAllBytes(manifest().toPath()),StandardCharsets.UTF_8));if(manifestRoot.getInt("schema")!=1)throw new IllegalStateException("Unsupported manifest");
        JSONObject expected=manifestRoot.getJSONObject("accounts").getJSONObject(label),tab=expected.getJSONArray("tabs").getJSONObject(index);
        String id=expected.getString("id"),storage=expected.getString("storageId"),tabId=tab.getString("id"),url=tab.getString("url");WorkspaceState.profile(id);WorkspaceState.profile(storage);WorkspaceState.profile(tabId);
        requireCurrent(id,storage,tabId,url);
        JSONObject saved=new EnvironmentRecoveryStore(getApplicationContext()).load(id,storage,tabId,url),document=saved==null?null:saved.optJSONObject("document");
        String draft=document==null?"":document.getString("draft"),preview=document==null?"":document.getString("preview");
        String relative="environment-recovery/"+id+"/"+storage+"/"+tabId+".bin";File primary=new File(getFilesDir(),relative),backup=new File(getFilesDir(),relative+".bak");
        JSONObject result=new JSONObject().put("schema",1).put("complete",true).put("account",label).put("tab",index).put("accountId",id).put("storageId",storage).put("tabId",tabId).put("url",url)
            .put("recordPresent",saved!=null).put("documentPresent",document!=null).put("draftSHA256",hash(draft.getBytes(StandardCharsets.UTF_8))).put("draftLength",draft.length()).put("previewLength",preview.length())
            .put("scroll",document==null?JSONObject.NULL:document.getDouble("scroll")).put("ratio",document==null?JSONObject.NULL:document.getDouble("ratio"))
            .put("nativeStatePresent",saved!=null&&!saved.optString("nativeSessionState","").isEmpty()).put("nativeStateLength",saved==null?0:saved.optString("nativeSessionState","").length())
            .put("capturedAt",saved==null?JSONObject.NULL:saved.getLong("capturedAt")).put("documentCapturedAt",document==null?JSONObject.NULL:document.optLong("capturedAt",0))
            .put("cipherSHA256",fileHash(primary)).put("backupCipherSHA256",fileHash(backup)).put("sampledAt",System.currentTimeMillis());
        requireCurrent(id,storage,tabId,url);writeProbe(result);
    }
    private void requireCurrent(String id,String storage,String tabId,String url)throws Exception{
        WorkspaceState state=new StateStore(getFilesDir()).load();WorkspaceState.Account account=state.find(id);
        if(!state.activeAccount.equals(id)||!account.isEnvironment()||!account.storageId.equals(storage)||!account.activeTab.equals(tabId)||!account.current().url.equals(url))throw new IllegalStateException("Current account or tab differs from probe target");
    }
    private static Object fileHash(File file)throws Exception{
        if(!Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return JSONObject.NULL;
        if(!Files.isRegularFile(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(file.toPath())>1024*1024+32)throw new IllegalStateException("Invalid encrypted record file");
        return hash(Files.readAllBytes(file.toPath()));
    }
    private static String hash(byte[] bytes)throws Exception{
        StringBuilder result=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();
    }
    private void writeProbe(JSONObject result)throws Exception{
        File target=new File(getFilesDir(),"environment-record-probe.json"),temporary=new File(getFilesDir(),"environment-record-probe.json.new");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(temporary)){out.write(result.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private void writeControlReceipt()throws Exception{
        JSONObject result=new JSONObject().put("schema",1).put("requestId",controlRequestId).put("package",getPackageName()).put("action",controlAction)
            .put("account",controlAccount==null?JSONObject.NULL:controlAccount).put("tab",controlTab==null?JSONObject.NULL:controlTab)
            .put("complete",true).put("success",controlSucceeded).put("errorClass",controlErrorClass==null?JSONObject.NULL:controlErrorClass)
            .put("destroyed",true).put("sampledAt",System.currentTimeMillis());
        File target=new File(getFilesDir(),"environment-control-"+controlRequestId+".json"),temporary=new File(target.getPath()+".new");
        if(target.exists())throw new IllegalStateException("Refuse control receipt overwrite");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(temporary)){out.write(result.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    @Override public void onDestroy(){
        if(networkControl!=null)networkControl.close();super.onDestroy();
        if(controlRequestId!=null&&controlCompleted){
            // Run after this destruction callback and its ActivityThread message have returned.
            // A following control must never reuse the instance that is still finishing.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                try{writeControlReceipt();}catch(Exception error){android.util.Log.e("EnvironmentAcceptance","Control completion receipt failed",error);}
            });
        }
    }

}
