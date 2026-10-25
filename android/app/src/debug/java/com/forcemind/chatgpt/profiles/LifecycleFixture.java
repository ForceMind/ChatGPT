package com.forcemind.chatgpt.profiles;

import android.app.Application;
import android.os.*;
import android.webkit.*;
import androidx.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Explicit private opt-in for CI. Completely absent from release APKs. */
final class LifecycleFixture {
    private static final String CONFIG="browser-lifecycle-fixture.json";
    static boolean present(Application app){return new File(app.getFilesDir(),CONFIG).isFile();}
    static void install(Application app){
        MainActivity.handoffFixture=(owner,request)->response("text/plain","Offline lifecycle fixture blocks external requests");
        try{new LifecycleFixture(app);}catch(Exception error){android.util.Log.e("LifecycleFixture","Invalid fixture; external requests stay blocked",error);}
    }
    private final Application app;private final String phase,template;private final Map<String,String> owners=new HashMap<>();
    private final Set<String> streamingTabs=new HashSet<>();
    private final Map<String,JSONObject> observations=new LinkedHashMap<>();private final Map<String,Integer> navigations=new HashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());private boolean flushPending;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"fixture-evidence");t.setDaemon(true);return t;});
    private LifecycleFixture(Application app)throws Exception{
        this.app=app;File config=new File(app.getFilesDir(),CONFIG);if(Files.isSymbolicLink(config.toPath())||config.length()>4096)throw new IOException("Invalid config");JSONObject data=new JSONObject(new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8));WorkspaceState.profile(data.getString("token"));phase=data.getString("phase");if(!phase.equals("seed")&&!phase.equals("verify"))throw new IOException("Invalid phase");
        boolean supported=WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);android.content.pm.PackageInfo provider=WebView.getCurrentWebViewPackage();JSONObject environment=new JSONObject().put("api",Build.VERSION.SDK_INT).put("android",Build.VERSION.RELEASE).put("model",Build.MODEL).put("abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS))).put("multiProfile",supported).put("webviewPackage",provider==null?"unknown":provider.packageName).put("webviewVersion",provider==null?"unknown":provider.versionName).put("phase",phase).put("pid",android.os.Process.myPid());write("lifecycle-environment.json",environment.toString());
        if(!supported){template="";return;}
        StateStore store=new StateStore(app.getFilesDir());WorkspaceState state;
        if(phase.equals("seed")){state=WorkspaceState.fresh();state.accounts.clear();for(int i=0;i<3;i++){WorkspaceState.Account account=new WorkspaceState.Account();account.name="CI 空间 "+(i+1);account.color=WorkspaceState.COLORS[i];account.tabs.clear();for(int n=0;n<(i==0?4:2);n++){WorkspaceState.Tab tab=new WorkspaceState.Tab();tab.title="Retention "+(i+1)+"-"+(n+1);tab.customTitle=true;tab.url="https://chatgpt.com/c/lifecycle-"+tab.id;account.tabs.add(tab);}account.activeTab=account.tabs.get(0).id;state.accounts.add(account);}state.activeAccount=state.accounts.get(0).id;store.save(state);}else state=store.load();
        for(WorkspaceState.Account account:state.accounts){streamingTabs.add(account.tabs.get(0).id);for(WorkspaceState.Tab tab:account.tabs)owners.put(tab.id,account.id);}
        try(InputStream in=app.getAssets().open("browser-lifecycle-fixture.html")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);template=new String(out.toByteArray(),StandardCharsets.UTF_8);}
        MainActivity.handoffFixture=(owner,request)->serve(owner,request);flush();
    }
    private WebResourceResponse serve(String account,WebResourceRequest request){
        try{
            android.net.Uri url=request.getUrl();if(!"chatgpt.com".equals(url.getHost()))return response("text/plain","Blocked outside fixture");String path=url.getPath();
            if(request.isForMainFrame()&&path!=null&&path.startsWith("/c/lifecycle-")){
                String tab=path.substring("/c/lifecycle-".length());if(!account.equals(owners.get(tab)))return response("text/plain","Wrong fixture owner");synchronized(this){navigations.put(tab,navigations.getOrDefault(tab,0)+1);}
                JSONObject config=new JSONObject().put("tab",tab).put("owner",account).put("phase",phase).put("streaming",streamingTabs.contains(tab)).put("draft",phase.equals("seed")?"SAVED_DRAFT_"+tab:"");return response("text/html",template.replace("__CONFIG__",config.toString()));
            }
            String tab=url.getQueryParameter("tab");if(tab==null||!account.equals(owners.get(tab)))return response("text/plain","Invalid fixture tab");
            if("/lifecycle-beacon".equals(path)){observe(tab,url);return response("text/plain","ok");}
            if("/lifecycle-poll".equals(path))return response("text/plain","event");
            if("/lifecycle-events".equals(path)&&streamingTabs.contains(tab)){
                PipedInputStream input=new PipedInputStream(65536);PipedOutputStream output=new PipedOutputStream(input);Thread writer=new Thread(()->{try(output){output.write((":"+String.join("",Collections.nCopies(16384," "))+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();for(int n=1;n<2000;n++){output.write(("data: "+n+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();Thread.sleep(1000);}}catch(Exception ignored){}},"fixture-events");writer.setDaemon(true);writer.start();Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("Content-Type","text/event-stream; charset=utf-8");headers.put("X-Content-Type-Options","nosniff");return new WebResourceResponse("text/event-stream","UTF-8",200,"OK",headers,input);
            }
        }catch(Exception ignored){}
        return response("text/plain","Offline fixture request rejected");
    }
    private synchronized void observe(String tab,android.net.Uri query)throws Exception{
        String instance=query.getQueryParameter("instance"),hash=query.getQueryParameter("draftHash");WorkspaceState.profile(instance);if(hash==null||!hash.matches("[a-f0-9]{64}"))return;
        JSONObject previous=observations.get(tab),entry=previous==null?new JSONObject().put("firstSeen",System.currentTimeMillis()).put("documents",0).put("times",new JSONArray()):previous;
        if(!instance.equals(entry.optString("instance")))entry.put("documents",entry.optInt("documents")+1);long now=System.currentTimeMillis();JSONArray times=entry.getJSONArray("times");times.put(now);while(times.length()>64)times.remove(0);
        entry.put("transport",streamingTabs.contains(tab)?"sse":"poll").put("streamStatus",query.getQueryParameter("streamStatus")).put("instance",instance).put("lastSeen",now).put("beats",number(query,"beats")).put("events",number(query,"events")).put("sent",number(query,"sent")).put("images",number(query,"images")).put("draftHash",hash).put("navigations",navigations.getOrDefault(tab,0));observations.put(tab,entry);
        if(!flushPending){flushPending=true;handler.postDelayed(this::flush,700);}
    }
    private int number(android.net.Uri uri,String key){try{int value=Integer.parseInt(uri.getQueryParameter(key));return Math.max(0,Math.min(1000000,value));}catch(Exception e){return 0;}}
    private synchronized void flush(){flushPending=false;JSONObject result=new JSONObject();try{result.put("phase",phase).put("observedAt",System.currentTimeMillis()).put("tabs",new JSONObject(observations));}catch(Exception ignored){}String bytes=result.toString();io.execute(()->write("lifecycle-observations.json",bytes));}
    private void write(String name,String value){try{Files.write(new File(app.getFilesDir(),name).toPath(),value.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}}
    private static WebResourceResponse response(String mime,String text){WebResourceResponse result=new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));result.setResponseHeaders(Collections.singletonMap("Cache-Control","no-store"));return result;}
}
