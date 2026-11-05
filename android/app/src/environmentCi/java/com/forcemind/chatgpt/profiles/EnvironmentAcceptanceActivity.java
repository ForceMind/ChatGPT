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
            if(!"prepare".equals(action)&&!"open".equals(action))throw new IllegalArgumentException("Unknown action");
            Bundle extras=intent.getExtras();if(extras!=null)for(String key:extras.keySet())if(!"action".equals(key)&&(!"open".equals(action)||!"account".equals(key)&&!"tab".equals(key)))throw new IllegalArgumentException("Unknown input");
            if("prepare".equals(action))prepare();else open(intent);
            setResult(RESULT_OK);finish();
        }catch(Exception error){android.util.Log.e("EnvironmentAcceptance","Acceptance command rejected",error);setResult(RESULT_CANCELED);finish();}
    }
    private File manifest(){return new File(getFilesDir(),MANIFEST);}
    private static String origin(){
        URI home=URI.create(BuildConfig.ENVIRONMENT_HOME_URL);
        if(!"https".equals(home.getScheme())||!"environment-fixture.test".equals(home.getHost())||home.getPort()!=18480||home.getRawUserInfo()!=null||home.getRawQuery()!=null||home.getRawFragment()!=null)throw new SecurityException("Unexpected acceptance origin");
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
}
