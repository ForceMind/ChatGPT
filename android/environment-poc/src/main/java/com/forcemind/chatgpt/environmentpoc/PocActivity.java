package com.forcemind.chatgpt.environmentpoc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.system.Os;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.mozilla.geckoview.*;

/** Standalone experiment: synthetic loopback pages, new profiles, no production code/data. */
public abstract class PocActivity extends Activity {
    private static GeckoRuntime runtime;
    private static final List<GeckoSession> tabs = new ArrayList<>();
    private static int active;
    private static String runtimeAccount;
    private GeckoView view;
    private TextView status;
    abstract String account();
    private String language() { return account().equals("A") ? "zh-CN" : "en-US"; }
    private String zone() { return account().equals("A") ? "Asia/Shanghai" : "America/New_York"; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this); title.setText("独立环境 PoC · 仅本地夹具"); title.setTextColor(Color.BLACK); title.setPadding(16,32,16,8); root.addView(title);
        LinearLayout controls = new LinearLayout(this); root.addView(controls);
        button(controls,"账号 A", () -> switchAccount("A",0)); button(controls,"账号 B", () -> switchAccount("B",0));
        button(controls,"重建",this::recreate);
        LinearLayout counts = new LinearLayout(this); root.addView(counts);
        for (int n : new int[]{1,4,8}) button(counts,n+" 标签",() -> resize(n));
        button(counts,"下个页签",() -> { if(!tabs.isEmpty()){active=(active+1)%tabs.size();showTab();} });
        status = new TextView(this); status.setTextColor(Color.DKGRAY); status.setPadding(16,8,16,8); root.addView(status);
        view = new GeckoView(this); root.addView(view,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        try {
            if(runtime == null) {
                // Defaults change only in this account's App process, never Android system settings.
                Locale locale = Locale.forLanguageTag(language()); Locale.setDefault(locale); LocaleList.setDefault(new LocaleList(locale));
                TimeZone.setDefault(TimeZone.getTimeZone(zone())); Os.setenv("TZ",zone(),true);
                File profile = new File(getFilesDir(),"poc-gecko-"+account()); if(!profile.isDirectory()&&!profile.mkdirs()) throw new IllegalStateException("Profile creation failed");
                Bundle env = new Bundle(); env.putString("env0","TZ="+zone());
                GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .arguments(new String[]{"-profile",profile.getAbsolutePath()}).extras(env)
                    .locales(new String[]{language(),language().split("-")[0]})
                    .remoteDebuggingEnabled(false).consoleOutput(true).build();
                runtime = GeckoRuntime.create(getApplicationContext(),settings); runtimeAccount=account();
            }
            if(!account().equals(runtimeAccount)) throw new IllegalStateException("Runtime/account mismatch");
            if(tabs.isEmpty())resize(1); else showTab();
            handle(getIntent());
        } catch(Exception e) { status.setText("实验启动失败："+e); record("startup-error",e.toString()); }
    }
    private void button(LinearLayout row,String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setTextColor(Color.BLACK);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v -> action.run());
    }
    private void switchAccount(String target,int count) {
        Intent intent=new Intent(this,target.equals("A")?AccountAActivity.class:AccountBActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);if(count>0)intent.putExtra("tabs",count);startActivity(intent);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent){
        String target=intent.getStringExtra("account");int count=intent.getIntExtra("tabs",0);
        if(target!=null&&!target.equals(account())) {
            Intent next=new Intent(this,target.equals("B")?AccountBActivity.class:AccountAActivity.class);
            next.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);next.putExtra("tabs",count);
            next.putExtra("next",intent.getBooleanExtra("next",false));next.putExtra("recreate",intent.getBooleanExtra("recreate",false));startActivity(next);return;
        }
        if(count==1||count==4||count==8)resize(count);
        if(intent.getBooleanExtra("recreate",false)){intent.removeExtra("recreate");recreate();}
        if(intent.getBooleanExtra("next",false)&&!tabs.isEmpty()){active=(active+1)%tabs.size();showTab();}
    }
    private boolean allowed(String address){
        Uri u=Uri.parse(address);return "http".equals(u.getScheme())&&u.getPort()==8765&&("127.0.0.1".equals(u.getHost())||"localhost".equals(u.getHost()));
    }
    private GeckoSession newSession(boolean open){
        GeckoSession s=new GeckoSession(new GeckoSessionSettings.Builder().contextId("poc-account-"+account()).build());
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,LoadRequest request){return GeckoResult.fromValue(allowed(request.uri)?AllowOrDeny.ALLOW:AllowOrDeny.DENY);}
            @Override public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session,LoadRequest request){return GeckoResult.fromValue(allowed(request.uri)?AllowOrDeny.ALLOW:AllowOrDeny.DENY);}
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session,String uri){
                if(!allowed(uri))return null;
                GeckoSession popup=newSession(false);tabs.add(popup);new Handler(Looper.getMainLooper()).postDelayed(()->{if(popup.isOpen())popup.setActive(true);record("popup-created",uri);},1000);return GeckoResult.fromValue(popup);
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession session,String uri,WebRequestError error){record("load-error",uri+" "+error);return null;}
        });
        if(open){s.open(runtime);s.setActive(true);}return s;
    }
    private void resize(int count){
        if(runtime==null)return;
        if(view.getSession()!=null)view.releaseSession();
        while(tabs.size()>count)tabs.remove(tabs.size()-1).close();
        while(tabs.size()<count){int index=tabs.size();GeckoSession s=newSession(true);tabs.add(s);s.loadUri("http://127.0.0.1:8765/page?account="+account()+"&tab="+index+"&pid="+android.os.Process.myPid());}
        active=Math.min(active,tabs.size()-1);showTab();
    }
    private void showTab(){
        if(view.getSession()!=null)view.releaseSession();view.setSession(tabs.get(active));tabs.get(active).setActive(true);
        status.setText("账号 "+account()+" · "+language()+" · "+zone()+"\n页签 "+(active+1)+" / "+tabs.size()+" · 调试接口关闭");record("visible","");new Handler(Looper.getMainLooper()).postDelayed(()->record("layout",""),1500);
    }
    private void record(String event,String detail){
        try{JSONObject json=new JSONObject().put("account",account()).put("pid",android.os.Process.myPid()).put("language",language()).put("timeZone",zone()).put("tabs",tabs.size()).put("event",event).put("detail",detail).put("remoteDebugging",false);
            int[] xy=new int[2];if(view!=null){view.getLocationOnScreen(xy);json.put("viewX",xy[0]).put("viewY",xy[1]).put("viewWidth",view.getWidth()).put("viewHeight",view.getHeight());}
            Files.write(new File(getFilesDir(),"native-"+account()+".json").toPath(),json.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){android.util.Log.e("EnvironmentPoC","Evidence write failed",e);}
    }
    @Override protected void onDestroy(){if(view!=null&&view.getSession()!=null)view.releaseSession();super.onDestroy();}
}
