package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import studio.networkcore.Networkcore;

/** DUMP-protected owned controls. No production node or credential can enter this fixture. */
final class EnvironmentNetworkControl {
    private final Activity activity;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final NetworkSession session;
    private String operation;
    private Runnable observer;
    private boolean done;
    EnvironmentNetworkControl(Activity activity){this.activity=activity;session=BrowserMemory.get(activity).network();}
    void run(Intent input){
        if(!BuildConfig.ENVIRONMENT_NETWORK_TEST||!BuildConfig.ENVIRONMENT_TEST)throw new SecurityException("Owned network control disabled");
        operation=input.getStringExtra("action");
        if(input.getExtras()!=null)for(String key:input.getExtras().keySet())if(!"action".equals(key)&&!("network-select".equals(operation)&&"index".equals(key)))throw new IllegalArgumentException("Unexpected network control input");
        ui.postDelayed(()->finish(false,"Owned network action timed out"),45000);
        try{
            if("network-status".equals(operation)){finish(true,"");return;}
            if("network-select".equals(operation)){Object value=input.getExtras().get("index");if(!(value instanceof Integer))throw new IllegalArgumentException("Integer route index required");int index=(Integer)value;if(index<0||index>=session.nodes().size())throw new IllegalArgumentException("Unknown owned route");session.select(index,this::finish);return;}
            if("network-retry".equals(operation)){session.retry(this::finish);return;}
            if("network-stop".equals(operation)){
                if(!session.routes.coreInitialized())throw new IllegalStateException("Core must already exist");
                new Thread(()->{try{Networkcore.stop();ui.post(()->awaitFailed());}catch(Exception failure){ui.post(()->finish(false,failure.getClass().getName()));}},"owned-core-stop").start();return;
            }
            if(!"network-config".equals(operation))throw new IllegalArgumentException("Unknown owned action");
            observer=()->{if(done||session.changing()||!session.ready())return;session.removePanelObserver(observer);observer=null;configure();};session.observePanel(observer);
        }catch(Exception failure){finish(false,failure.getClass().getName());}
    }
    private void configure(){
        try{
            if(!session.nodes().isEmpty())throw new IllegalStateException("Only an empty owned network configuration may be initialized");
            File file=new File(activity.getFilesDir(),"gecko-network-config.json");if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.size(file.toPath())>65536)throw new IllegalArgumentException("Missing owned network configuration");
            JSONObject config=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));if(config.getInt("schema")!=1)throw new IllegalArgumentException("Unknown configuration schema");JSONArray values=config.getJSONArray("nodes");if(values.length()<2||values.length()>8)throw new IllegalArgumentException("Owned route count");int selected=config.getInt("selected");if(selected<0||selected>=values.length())throw new IllegalArgumentException("Owned selected route");
            ArrayList<NetworkNode> nodes=new ArrayList<>();for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);NetworkNode node=NetworkNode.parse(value.getString("uri"),value.getString("name"));if(!"10.0.2.2".equals(node.host)||!java.util.Arrays.asList(18443,18444,19443,19444,19543,20443,20444).contains(node.port))throw new SecurityException("Non-owned node refused");nodes.add(node);}save(nodes,0,selected);
        }catch(Exception failure){finish(false,failure.getClass().getName());}
    }
    private void save(ArrayList<NetworkNode> nodes,int index,int selected){
        if(done)return;if(index==nodes.size()){session.select(selected,this::finish);return;}
        session.saveNode(-1,nodes.get(index),(ok,message)->{if(!ok)finish(false,message);else save(nodes,index+1,selected);});
    }
    private void awaitFailed(){observer=()->{if(!done&&session.routes.snapshot().state==NetworkRoute.State.FAILED)finish(true,"");};session.observePanel(observer);}
    private void finish(boolean ok,String message){
        if(done)return;done=true;ui.removeCallbacksAndMessages(null);if(observer!=null){session.removePanelObserver(observer);observer=null;}
        try{
            NetworkRoute.Snapshot current=session.routes.snapshot();boolean running=session.routes.coreInitialized()&&Networkcore.isRunning();
            JSONObject status=new JSONObject().put("schema",1).put("sampledAt",System.currentTimeMillis()).put("complete",true).put("operation",operation).put("ok",ok).put("state",current.state.name()).put("selected",session.selected()).put("enabled",session.enabled()).put("generation",current.generation).put("coreRunning",running).put("port",current.port).put("busy",session.controlsBusy()).put("status",session.status()).put("error",message.isEmpty()?session.routes.error():message);
            File root=activity.getFilesDir().getCanonicalFile(),target=new File(root,"gecko-network-status.json"),next=new File(root,"gecko-network-status.json.new");try(FileOutputStream output=new FileOutputStream(next)){output.write(status.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}Files.move(next.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);activity.setResult(Activity.RESULT_OK);
        }catch(Exception failure){android.util.Log.e("OwnedGeckoNetwork","Cannot retain action receipt",failure);activity.setResult(Activity.RESULT_CANCELED);}
        activity.finish();
    }
    void close(){if(!done){done=true;ui.removeCallbacksAndMessages(null);if(observer!=null)session.removePanelObserver(observer);}}
}
