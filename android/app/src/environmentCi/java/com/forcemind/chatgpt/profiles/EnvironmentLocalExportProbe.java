package com.forcemind.chatgpt.profiles;

import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fresh synthetic CI only. Observes real stages; cannot authorize or fabricate a file. */
final class EnvironmentLocalExportProbe implements EnvironmentLocalExportChecks.Probe {
    private static boolean installed;
    private final File root;
    private final JSONArray events=new JSONArray();
    private int sequence;
    private EnvironmentLocalExportProbe(File files)throws IOException{root=files.getCanonicalFile();}
    static synchronized void install(File files){
        if(!BuildConfig.ENVIRONMENT_TEST||!BuildConfig.ENVIRONMENT_NETWORK_TEST)throw new SecurityException("Owned local-export CI only");
        if(installed)return;
        try{EnvironmentLocalExportChecks.install(new EnvironmentLocalExportProbe(files));installed=true;}
        catch(IOException failure){throw new IllegalStateException("Cannot install owned local-export evidence",failure);}
    }
    @Override public synchronized void stage(String stage,String operation,String accountId,String tabId,long bytes){
        try{
            if(!stage.matches("[a-z0-9-]{1,40}")||bytes<0)throw new IOException("Invalid native stage evidence");
            events.put(new JSONObject().put("sequence",++sequence).put("pid",Process.myPid()).put("stage",stage)
                .put("operation",operation).put("accountId",accountId).put("tabId",tabId).put("bytes",bytes)
                .put("elapsedMillis",SystemClock.elapsedRealtime()));
            if(events.length()>256)throw new IOException("Owned evidence capacity exceeded");
            JSONObject value=new JSONObject().put("schema",1).put("pid",Process.myPid()).put("events",events);
            File target=new File(root,"environment-local-exports-events-"+Process.myPid()+".json"),next=new File(root,target.getName()+".new");
            try(FileOutputStream output=new FileOutputStream(next)){output.write(value.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(next.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception failure){throw new IllegalStateException("Cannot preserve owned local-export stage",failure);}
    }
    private boolean hold()throws IOException{
        File control=new File(root,"environment-local-exports-control.json");
        if(!Files.exists(control.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
        if(!Files.isRegularFile(control.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.size(control.toPath())>4096)throw new IOException("Invalid owned export control");
        try{
            JSONObject value=new JSONObject(new String(Files.readAllBytes(control.toPath()),StandardCharsets.UTF_8));
            if(value.getInt("schema")!=1||!(value.get("hold") instanceof Boolean))throw new IOException("Unknown owned export control");
            return value.getBoolean("hold");
        }catch(org.json.JSONException invalid){throw new IOException("Malformed owned export control",invalid);}
    }
    @Override public void beforePublish(String operation,String accountId,String tabId,long bytes,BooleanSupplier valid)throws IOException{
        if(!hold())return;
        long deadline=SystemClock.elapsedRealtime()+20000;
        stage("publication-held",operation,accountId,tabId,bytes);
        while(hold()){
            if(!valid.getAsBoolean())throw new InterruptedIOException("Original local-export owner became invalid");
            if(SystemClock.elapsedRealtime()>=deadline)throw new InterruptedIOException("Owned publication control timed out");
            try{Thread.sleep(25);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new InterruptedIOException("Owned publication control interrupted");}
        }
        if(!valid.getAsBoolean())throw new InterruptedIOException("Original local-export owner became invalid");
        stage("publication-released",operation,accountId,tabId,bytes);
    }
}
