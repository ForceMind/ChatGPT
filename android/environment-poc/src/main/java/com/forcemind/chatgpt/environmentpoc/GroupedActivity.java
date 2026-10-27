package com.forcemind.chatgpt.environmentpoc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.system.Os;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.AllowOrDeny;

/** Isolated synthetic experiment; never opens the production app or real sites. */
public abstract class GroupedActivity extends Activity {
    private static GeckoRuntime runtime;
    private static String runtimeEnvironment,runtimeMode;
    private static final Map<String,List<GeckoSession>> sessions=new HashMap<>();
    private static final Map<String,Integer> selected=new HashMap<>();
    private GeckoView view;
    private TextView status;
    abstract String account();
    private String language(){return account().equals("B")?"en-US":"zh-CN";}
    private String zone(){return account().equals("B")?"America/New_York":"Asia/Shanghai";}
    private String mode(){String value=getIntent().getStringExtra("mode");return "control".equals(value)?"control":"grouped";}
    private String context(){return "grouped-poc-"+mode()+"-"+account();}
    private static List<GeckoSession> tabsFor(String id){return sessions.computeIfAbsent(id,key->new ArrayList<>());}
    private List<GeckoSession> tabs(){return tabsFor(account());}
    private static boolean allowed(String address){Uri u=Uri.parse(address);return "http".equals(u.getScheme())&&u.getPort()==8765&&("127.0.0.1".equals(u.getHost())||"localhost".equals(u.getHost()));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        TextView heading=new TextView(this);heading.setText("独立分组验证 · 仅本地夹具");heading.setTextColor(Color.BLACK);heading.setPadding(16,32,16,8);root.addView(heading);
        LinearLayout controls=new LinearLayout(this);root.addView(controls);
        for(String id:new String[]{"A","B","C"})button(controls,"账号 "+id,()->switchAccount(id,0,false,false,false,false));
        button(controls,"下个标签",()->{if(!tabs().isEmpty()){selected.put(account(),(selected.getOrDefault(account(),0)+1)%tabs().size());showTab();}});
        status=new TextView(this);status.setTextColor(Color.DKGRAY);status.setPadding(16,8,16,8);root.addView(status);
        view=new GeckoView(this);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        try{
            String environment=language()+"/"+zone();
            if(runtime==null){
                Locale locale=Locale.forLanguageTag(language());Locale.setDefault(locale);LocaleList.setDefault(new LocaleList(locale));
                TimeZone.setDefault(TimeZone.getTimeZone(zone()));Os.setenv("TZ",zone(),true);
                String slot=account().equals("B")?"b":(mode().equals("control")&&account().equals("C")?"c":"a");
                File profile=new File(getFilesDir(),"grouped-poc-"+mode()+"-"+slot);if(!profile.isDirectory()&&!profile.mkdirs())throw new IllegalStateException("Profile creation failed");
                Bundle env=new Bundle();env.putString("env0","TZ="+zone());
                GeckoRuntimeSettings settings=new GeckoRuntimeSettings.Builder().arguments(new String[]{"-profile",profile.getAbsolutePath()}).extras(env)
                    .locales(new String[]{language(),language().split("-")[0]}).remoteDebuggingEnabled(false).consoleOutput(true).build();
                runtime=GeckoRuntime.create(getApplicationContext(),settings);runtimeEnvironment=environment;runtimeMode=mode();
            }
            if(!environment.equals(runtimeEnvironment)||!mode().equals(runtimeMode))throw new IllegalStateException("Runtime environment mismatch");
            boolean action=getIntent().getBooleanExtra("clear",false)||getIntent().getBooleanExtra("probe",false);
            if(tabs().isEmpty()&&!action)resize(1,false);else if(!tabs().isEmpty())showTab();handle(getIntent());
        }catch(Exception e){status.setText("实验启动失败："+e);record("startup-error",e.toString());}
    }
    private void button(LinearLayout row,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextColor(Color.BLACK);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->action.run());}
    private void switchAccount(String target,int count,boolean probe,boolean clear,boolean nextTab,boolean recreate){
        Class<? extends GroupedActivity> cls=target.equals("B")?GroupedBActivity.class:target.equals("C")?(mode().equals("control")?GroupedCControlActivity.class:GroupedCActivity.class):GroupedAActivity.class;
        Intent next=new Intent(this,cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("mode",mode()).putExtra("account",target);
        if(count>0)next.putExtra("tabs",count);if(probe)next.putExtra("probe",true);if(clear)next.putExtra("clear",true);if(nextTab)next.putExtra("next",true);if(recreate)next.putExtra("recreate",true);startActivity(next);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent){
        String target=intent.getStringExtra("account");int count=intent.getIntExtra("tabs",0);boolean probe=intent.getBooleanExtra("probe",false),clear=intent.getBooleanExtra("clear",false);
        if(target!=null&&!target.equals("A")&&!target.equals("B")&&!target.equals("C")){record("invalid-target","");return;}
        if(target!=null&&!target.equals(account())){switchAccount(target,count,probe,clear,intent.getBooleanExtra("next",false),intent.getBooleanExtra("recreate",false));return;}
        if(clear){clearAccount();intent.removeExtra("clear");return;}
        if(probe){resize(0,false);resize(1,true);intent.removeExtra("probe");}
        else if(count>0&&count<=4)resize(count,false);
        if(intent.getBooleanExtra("next",false)&&!tabs().isEmpty()){selected.put(account(),(selected.getOrDefault(account(),0)+1)%tabs().size());showTab();intent.removeExtra("next");}
        if(intent.getBooleanExtra("recreate",false)){intent.removeExtra("recreate");recreate();}
    }
    private static GeckoSession newSession(String owner,String context){
        GeckoSession session=new GeckoSession(new GeckoSessionSettings.Builder().contextId(context).build());
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s,LoadRequest r){return GeckoResult.fromValue(allowed(r.uri)?AllowOrDeny.ALLOW:AllowOrDeny.DENY);}
            @Override public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession s,LoadRequest r){return GeckoResult.fromValue(allowed(r.uri)?AllowOrDeny.ALLOW:AllowOrDeny.DENY);}
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s,String uri){
                if(!allowed(uri))return null;GeckoSession popup=newSession(owner,context);tabsFor(owner).add(popup);
                new Handler(Looper.getMainLooper()).postDelayed(()->{if(popup.isOpen())popup.setActive(true);},1000);return GeckoResult.fromValue(popup);
            }
        });return session;
    }
    private void resize(int count,boolean probe){
        if(runtime==null)return;if(view.getSession()!=null)view.releaseSession();
        while(tabs().size()>count)tabs().remove(tabs().size()-1).close();
        while(tabs().size()<count){int index=tabs().size();GeckoSession s=newSession(account(),context());s.open(runtime);s.setActive(true);tabs().add(s);
            s.loadUri("http://127.0.0.1:8765/page?account="+account()+"&tab="+index+"&mode="+mode()+"&probe="+probe+"&pid="+android.os.Process.myPid());}
        selected.put(account(),Math.max(0,Math.min(selected.getOrDefault(account(),0),count-1)));if(count>0)showTab();
    }
    private void clearAccount(){
        resize(0,false);runtime.getStorageController().clearDataForSessionContext(context());record("clear-requested",context());
    }
    private void showTab(){
        if(view.getSession()!=null)view.releaseSession();view.setSession(tabs().get(selected.getOrDefault(account(),0)));tabs().get(selected.getOrDefault(account(),0)).setActive(true);
        status.setText("账号 "+account()+" · "+language()+" · "+zone()+"\n标签 "+(selected.getOrDefault(account(),0)+1)+" / "+tabs().size()+" · "+mode());record("visible","");
        new Handler(Looper.getMainLooper()).postDelayed(()->record("layout",""),1000);
    }
    private void record(String event,String detail){
        try{JSONObject data=new JSONObject().put("account",account()).put("mode",mode()).put("pid",android.os.Process.myPid()).put("language",language()).put("timeZone",zone()).put("tabs",tabs().size()).put("context",context()).put("event",event).put("detail",detail).put("remoteDebugging",false);
            int[] xy=new int[2];if(view!=null){view.getLocationOnScreen(xy);data.put("viewX",xy[0]).put("viewY",xy[1]).put("viewWidth",view.getWidth()).put("viewHeight",view.getHeight());}
            byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);Files.write(new File(getFilesDir(),"grouped-native-"+mode()+"-"+account()+".json").toPath(),bytes);
            if(event.equals("clear-requested"))Files.write(new File(getFilesDir(),"grouped-clear-"+mode()+"-"+account()+".json").toPath(),bytes);
        }catch(Exception e){android.util.Log.e("GroupedEnvironmentPoC","Evidence write failed",e);}
    }
    @Override protected void onDestroy(){if(view!=null&&view.getSession()!=null)view.releaseSession();super.onDestroy();}
}
