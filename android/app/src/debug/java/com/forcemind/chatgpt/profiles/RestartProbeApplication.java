package com.forcemind.chatgpt.profiles;

import android.app.Application;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;

/** Opt-in private fixture, present only in the debug APK used by isolated CI. */
public final class RestartProbeApplication extends Application {
    @Override public void onCreate(){
        super.onCreate();if(LifecycleFixture.present(this)){LifecycleFixture.install(this);return;}File config=new File(getFilesDir(),"process-restart-fixture.json");if(!config.exists())return;
        // A malformed opt-in fixture must not fall through to a real network request.
        MainActivity.handoffFixture=(owner,request)->response("text/plain","Offline restart fixture unavailable");
        try{
            if(Files.isSymbolicLink(config.toPath())||config.length()>4096)throw new IOException("Invalid fixture file");
            JSONObject data=new JSONObject(new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8));
            WorkspaceState.profile(data.getString("token"));String account=data.getString("accountId");WorkspaceState.profile(account);
            String phase=data.getString("phase");if(!phase.equals("seed")&&!phase.equals("verify"))throw new IOException("Invalid phase");
            String draft=phase.equals("seed")?HandoffRequest.string(data,"draft",200,true):"";
            String template;try(InputStream input=getAssets().open("process-restart-fixture.html")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)out.write(b,0,n);template=new String(out.toByteArray(),StandardCharsets.UTF_8);}
            String page=template.replace("__FIXTURE_CONFIG__",new JSONObject().put("phase",phase).put("draft",draft).toString());
            MainActivity.handoffFixture=(owner,request)->{
                if(account.equals(owner)&&"chatgpt.com".equals(request.getUrl().getHost())&&"/c/process-restart-fixture".equals(request.getUrl().getPath()))return response("text/html",page);
                return response("text/plain","Offline restart fixture blocks external requests");
            };
        }catch(Exception error){android.util.Log.e("RestartProbe","Invalid opt-in fixture; network remains blocked",error);}
    }
    private static WebResourceResponse response(String mime,String body){return new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));}
}
