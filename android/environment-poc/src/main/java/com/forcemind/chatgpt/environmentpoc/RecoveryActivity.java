package com.forcemind.chatgpt.environmentpoc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.util.AtomicFile;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

/** Native-session recovery measurement only; no page script injection or real URLs. */
public abstract class RecoveryActivity extends Activity {
    private static GeckoRuntime runtime;
    private static String environment;
    private static RecoveryStore store;
    private static final Map<String,List<Tab>> sessions=new HashMap<>();
    private static final Map<String,Integer> selected=new HashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private GeckoView view;
    private TextView status;
    private static final class Tab {
        final String owner,id;final GeckoSession session;
        boolean restored,blocked;String location="",title="",error="",hash="";
        int callbacks,historyCount,historyIndex;long savedAt,lastCallback,flushRequested;
        Tab(String owner,int index){this.owner=owner;id=Integer.toString(index);session=new GeckoSession(new GeckoSessionSettings.Builder().contextId("recovery-poc-"+owner).build());}
    }
    abstract String account();
    private List<Tab> tabs(){return sessions.computeIfAbsent(account(),key->new ArrayList<>());}
    private static boolean allowed(String uri,String owner,String tab){
        if(uri==null)return false;Uri u=Uri.parse(uri);
        return "http".equals(u.getScheme())&&"127.0.0.1".equals(u.getHost())&&u.getPort()==8765&&"/recovery".equals(u.getPath())
            &&owner.equals(u.getQueryParameter("account"))&&tab.equals(u.getQueryParameter("tab"))&&("start".equals(u.getQueryParameter("page"))||"detail".equals(u.getQueryParameter("page")));
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        TextView heading=new TextView(this);heading.setText("独立页面恢复验证 · 仅合成数据");heading.setTextColor(Color.BLACK);heading.setPadding(12,24,12,4);root.addView(heading);
        LinearLayout controls=new LinearLayout(this);root.addView(controls);
        for(String id:new String[]{"A","B","C"})button(controls,id,()->route(id,null,0));
        button(controls,"下个标签",()->{selected.put(account(),(selected.getOrDefault(account(),0)+1)%tabs().size());show();saveWorkspace();});
        button(controls,"保存关闭",()->checkpoint(true));button(controls,"重试",()->{if(!tabs().isEmpty())tabs().get(selected.getOrDefault(account(),0)).session.reload();});
        status=new TextView(this);status.setTextColor(Color.BLACK);root.addView(status);
        view=new GeckoView(this);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        try{
            if(!getIntent().hasExtra("account")){
                File last=new File(getFilesDir(),"recovery-last-account");
                if(last.exists()){String id=new String(Files.readAllBytes(last.toPath()),StandardCharsets.US_ASCII);if(id.matches("[ABC]")&&!id.equals(account())){route(id,null,0);finish();return;}}
            }
            String target=getIntent().getStringExtra("account");
            if(target!=null&&!target.equals(account())){if(!target.matches("[ABC]"))throw new IllegalArgumentException("Invalid account");route(target,getIntent().getStringExtra("action"),getIntent().getIntExtra("index",0));finish();return;}
            initialise();handle(getIntent());
        }catch(Exception error){status.setText("实验启动失败："+error.getClass().getSimpleName());record("startup-error",error.toString());}
    }
    private void initialise()throws Exception{
        String lang=account().equals("B")?"en-US":"zh-CN",zone=account().equals("B")?"America/New_York":"Asia/Shanghai",wanted=lang+"/"+zone;
        if(runtime==null){
            Locale locale=Locale.forLanguageTag(lang);Locale.setDefault(locale);LocaleList.setDefault(new LocaleList(locale));TimeZone.setDefault(TimeZone.getTimeZone(zone));Os.setenv("TZ",zone,true);
            File profile=new File(getFilesDir(),"recovery-poc-"+(account().equals("B")?"b":"a"));if(!profile.isDirectory()&&!profile.mkdirs())throw new IllegalStateException("Cannot create profile");
            Bundle env=new Bundle();env.putString("env0","TZ="+zone);
            runtime=GeckoRuntime.create(getApplicationContext(),new GeckoRuntimeSettings.Builder().arguments(new String[]{"-profile",profile.getAbsolutePath()}).extras(env).locales(new String[]{lang}).remoteDebuggingEnabled(false).consoleOutput(true).build());
            environment=wanted;store=new RecoveryStore(getApplicationContext());
        }
        if(!wanted.equals(environment))throw new IllegalStateException("Environment mismatch");
        if(tabs().isEmpty()){
            JSONObject workspace=store.read(account(),"workspace");int count=workspace==null?getIntent().getIntExtra("tabs",1):workspace.getInt("count");
            if(count<1||count>2)throw new IllegalStateException("Invalid tab count");
            selected.put(account(),workspace==null?0:workspace.getInt("selected"));if(selected.get(account())<0||selected.get(account())>=count)throw new IllegalStateException("Invalid active tab");
            for(int i=0;i<count;i++){Tab tab=make(i);tabs().add(tab);tab.session.open(runtime);tab.session.setActive(true);
                try{
                    JSONObject saved=store.read(account(),tab.id);
                    if(saved!=null){
                        GeckoSession.SessionState state=GeckoSession.SessionState.fromString(saved.getString("state"));
                        if(state==null||state.isEmpty())throw new IllegalStateException("Empty saved state");
                        for(GeckoSession.HistoryDelegate.HistoryItem item:state)if(!allowed(item.getUri(),account(),tab.id))throw new IllegalStateException("Foreign session history");
                        tab.restored=true;tab.hash=digest(saved.getString("state"));tab.historyCount=state.size();tab.historyIndex=state.getCurrentIndex();tab.session.restoreState(state);
                    }else tab.session.loadUri("http://127.0.0.1:8765/recovery?account="+account()+"&tab="+tab.id+"&page=start");
                }catch(Exception error){tab.blocked=true;tab.error="saved-record-rejected:"+error.getClass().getSimpleName();record("record-rejected",tab.id+":"+tab.error);}
            }
            if(workspace==null)saveWorkspace();
        }
        show();
    }
    private Tab make(int index){
        Tab tab=new Tab(account(),index);
        tab.session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s,LoadRequest r){boolean ok=allowed(r.uri,tab.owner,tab.id);record(ok?"load-allowed":"load-denied",r.uri);return GeckoResult.fromValue(ok?AllowOrDeny.ALLOW:AllowOrDeny.DENY);}
            @Override public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession s,LoadRequest r){return GeckoResult.fromValue(AllowOrDeny.DENY);}
            @Override public void onLocationChange(GeckoSession s,String uri,List<GeckoSession.PermissionDelegate.ContentPermission> permissions,Boolean gesture){tab.location=uri==null?"":uri;record("location",tab.id);}
        });
        tab.session.setContentDelegate(new GeckoSession.ContentDelegate(){
            @Override public void onTitleChange(GeckoSession s,String title){tab.title=title;record("title",tab.id);}
            @Override public void onCrash(GeckoSession s){tab.error="content-crash";record("content-crash",tab.id);}
            @Override public void onKill(GeckoSession s){tab.error="content-kill";record("content-kill",tab.id);}
        });
        tab.session.setProgressDelegate(new GeckoSession.ProgressDelegate(){
            @Override public void onSessionStateChange(GeckoSession s,GeckoSession.SessionState state){
                tab.callbacks++;tab.lastCallback=SystemClock.elapsedRealtime();tab.historyCount=state.size();tab.historyIndex=state.getCurrentIndex();
                // Restored experiments intentionally preserve the baseline record for repeated measurements.
                if(!tab.restored&&!tab.blocked)try{
                    if(state.isEmpty())return;for(GeckoSession.HistoryDelegate.HistoryItem item:state)if(!allowed(item.getUri(),tab.owner,tab.id))return;
                    String serialized=state.toString();store.write(tab.owner,tab.id,new JSONObject().put("state",serialized).put("engine","158.0.20261021001945"));tab.hash=digest(serialized);tab.savedAt=SystemClock.elapsedRealtime();
                }catch(Exception error){tab.error="save-failed:"+error.getClass().getSimpleName();}
                record("session-state",tab.id);
            }
        });return tab;
    }
    private static String digest(String text)throws Exception{byte[] sum=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:sum)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private void saveWorkspace(){if(tabs().stream().anyMatch(tab->tab.restored||tab.blocked))return;try{store.write(account(),"workspace",new JSONObject().put("count",tabs().size()).put("selected",selected.getOrDefault(account(),0)));}catch(Exception error){record("workspace-error",error.toString());}}
    private void checkpoint(boolean close){
        long started=SystemClock.elapsedRealtime();for(List<Tab> group:sessions.values())for(Tab tab:group){tab.flushRequested=started;tab.session.flushSessionState();}saveWorkspace();record("checkpoint-request","");
        Runnable ready=new Runnable(){public void run(){boolean done=true;for(List<Tab> group:sessions.values())for(Tab tab:group)done&=tab.lastCallback>=started;
            if(!done&&SystemClock.elapsedRealtime()-started<10000){handler.postDelayed(this,200);return;}
            record(done?"checkpoint-ack":"checkpoint-timeout","");
            if(close){release();for(List<Tab> group:sessions.values())for(Tab tab:group)tab.session.close();sessions.clear();record("closed","");finishAndRemoveTask();}
        }};handler.postDelayed(ready,200);
    }
    private void button(LinearLayout row,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextColor(Color.BLACK);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->action.run());}
    private void route(String target,String action,int index){Class<?> cls=target.equals("B")?RecoveryBActivity.class:target.equals("C")?RecoveryCActivity.class:RecoveryAActivity.class;Intent next=new Intent(this,cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("account",target);if(action!=null)next.putExtra("action",action);if(getIntent().hasExtra("sampleText"))next.putExtra("sampleText",getIntent().getStringExtra("sampleText"));next.putExtra("index",index);startActivity(next);}
    private void handle(Intent intent){try{String target=intent.getStringExtra("account"),action=intent.getStringExtra("action");if(target!=null&&!target.matches("[ABC]"))throw new IllegalArgumentException("Invalid account");if(target!=null&&!target.equals(account())){route(target,action,intent.getIntExtra("index",0));return;}
        if(runtime==null)return;if(tabs().isEmpty())initialise();
        if("select".equals(action)){int index=intent.getIntExtra("index",0);if(index<0||index>=tabs().size())throw new IllegalArgumentException("Invalid tab");selected.put(account(),index);show();saveWorkspace();}
        if("checkpoint".equals(action))checkpoint(false);if("quit".equals(action))checkpoint(true);
        if("recreate".equals(action)){intent.removeExtra("action");recreate();}
        if("back".equals(action))tabs().get(selected.getOrDefault(account(),0)).session.goBack();
        if("forward".equals(action))tabs().get(selected.getOrDefault(account(),0)).session.goForward();
        if("retry".equals(action))tabs().get(selected.getOrDefault(account(),0)).session.reload();
        if("input".equals(action)){String sample=intent.getStringExtra("sampleText");String prefix="draft-"+account()+"-"+selected.getOrDefault(account(),0)+"-";if(sample==null||!sample.matches(java.util.regex.Pattern.quote(prefix)+"[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid synthetic input");
            handler.postDelayed(()->{try{if(!view.hasWindowFocus())throw new IllegalStateException("Editor window is not focused");InputConnection input=view.onCreateInputConnection(new EditorInfo());if(input==null||!input.commitText(sample,1))throw new IllegalStateException("Editor input rejected");record("input-committed",digest(sample));}catch(Exception error){record("input-error",error.toString());}},500);}
        if("hideKeyboard".equals(action))((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(),0);
        handler.postDelayed(()->record("inspected",""),800);
    }catch(Exception error){record("command-error",error.toString());}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void release(){if(view!=null&&view.getSession()!=null){GeckoSession old=view.releaseSession();if(old!=null&&old.isOpen())old.setActive(true);}}
    private static void retain(){for(List<Tab> group:sessions.values())for(Tab tab:group)if(tab.session.isOpen())tab.session.setActive(true);}
    private void show(){if(tabs().isEmpty())return;release();Tab tab=tabs().get(selected.getOrDefault(account(),0));view.setSession(tab.session);tab.session.setActive(true);status.setText("账号 "+account()+" · 标签 "+(selected.getOrDefault(account(),0)+1)+"/"+tabs().size()+" · "+(tab.restored?"原生状态恢复测量":"首次测试页面"));
        try{AtomicFile last=new AtomicFile(new File(getFilesDir(),"recovery-last-account"));FileOutputStream out=last.startWrite();out.write(account().getBytes(StandardCharsets.US_ASCII));last.finishWrite(out);}catch(Exception error){record("last-account-error",error.toString());}
        record("visible","");handler.postDelayed(()->record("layout",""),500);
    }
    private void record(String event,String detail){try{
        JSONArray tabs=new JSONArray();for(Tab tab:tabs())tabs.put(new JSONObject().put("index",tab.id).put("open",tab.session.isOpen()).put("restored",tab.restored).put("blocked",tab.blocked).put("error",tab.error).put("location",tab.location).put("title",tab.title).put("callbacks",tab.callbacks).put("lastCallback",tab.lastCallback).put("flushRequested",tab.flushRequested).put("savedAt",tab.savedAt).put("stateSha256",tab.hash).put("historyCount",tab.historyCount).put("historyIndex",tab.historyIndex));
        JSONObject row=new JSONObject().put("account",account()).put("pid",android.os.Process.myPid()).put("selected",selected.getOrDefault(account(),0)).put("event",event).put("detail",detail).put("tabs",tabs).put("elapsedMs",SystemClock.elapsedRealtime());
        if(view!=null){int[] xy=new int[2];view.getLocationOnScreen(xy);row.put("viewX",xy[0]).put("viewY",xy[1]).put("viewWidth",view.getWidth()).put("viewHeight",view.getHeight()).put("viewShown",view.isShown()).put("windowFocused",view.hasWindowFocus()).put("viewFocused",view.hasFocus());}
        byte[] bytes=row.toString().getBytes(StandardCharsets.UTF_8);Files.write(new File(getFilesDir(),"recovery-native-"+account()+".json").toPath(),bytes);
        try(FileOutputStream log=new FileOutputStream(new File(getFilesDir(),"recovery-events-"+account()+".jsonl"),true)){log.write(bytes);log.write('\n');}
    }catch(Exception error){android.util.Log.e("RecoveryEnvironmentPoC","Evidence failed",error);}}
    @Override protected void onStop(){super.onStop();retain();handler.postDelayed(RecoveryActivity::retain,500);}
    @Override protected void onResume(){super.onResume();if(view!=null){view.requestFocus();if(view.getSession()!=null)view.getSession().setFocused(true);handler.postDelayed(()->record("resumed",""),600);}}
    @Override protected void onDestroy(){release();super.onDestroy();}
}
