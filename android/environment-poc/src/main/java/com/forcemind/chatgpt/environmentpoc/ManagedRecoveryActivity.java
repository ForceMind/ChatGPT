package com.forcemind.chatgpt.environmentpoc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Independent lab UI; formal app and its data are never loaded. */
public abstract class ManagedRecoveryActivity extends Activity {
    private static final Map<String,ManagedRecoveryActivity> hosts=new HashMap<>();
    private static final Map<String,List<ManagedPageController.Entry>> groups=new HashMap<>();
    private static final Map<String,Integer> selected=new HashMap<>();
    private static GeckoRuntime runtime;private static String environment;private static RecoveryStore store;private static ManagedPageController controller;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private GeckoView web;private TextView status,preview;private LinearLayout overlay,conflictControls;private Button retry;
    private boolean creating=true;private String startupError="";
    abstract String account();
    private List<ManagedPageController.Entry> tabs(){return groups.computeIfAbsent(account(),key->new ArrayList<>());}
    private ManagedPageController.Entry current(){return tabs().isEmpty()?null:tabs().get(selected.getOrDefault(account(),0));}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);hosts.put(account(),this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        TextView heading=label("独立恢复控制器 · 本地合成网页",16);root.addView(heading);
        LinearLayout controls=new LinearLayout(this);root.addView(controls);
        for(String id:new String[]{"A","B","C"})button(controls,id,()->startAccount(id));
        button(controls,"标签",()->{if(tabs().size()>1)select((selected.getOrDefault(account(),0)+1)%tabs().size());});
        button(controls,"保存关闭",()->checkpoint(true));button(controls,"保留草稿",this::backups);
        status=label("正在打开网页",14);root.addView(status);
        FrameLayout content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        web=new GeckoView(this);content.addView(web,new FrameLayout.LayoutParams(-1,-1));
        overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.VERTICAL);overlay.setBackgroundColor(Color.WHITE);overlay.setPadding(18,12,18,12);content.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        preview=label("",16);preview.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(preview);overlay.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        conflictControls=new LinearLayout(this);overlay.addView(conflictControls);button(conflictControls,"保留网页草稿",()->choose(false));button(conflictControls,"恢复上次草稿",()->choose(true));
        retry=new Button(this);retry.setText("重新连接");retry.setTextColor(Color.BLACK);overlay.addView(retry);retry.setOnClickListener(v->{if(current()!=null)controller.retry(current());});
        setContentView(root);
        try{
            if(store==null)store=new RecoveryStore(getApplicationContext(),true);
            if(!getIntent().hasExtra("account")){
                JSONObject index=store.read("A","index");String target=index==null?"A":index.getString("activeAccount");if(!target.matches("[ABC]"))throw new IllegalArgumentException("Invalid saved account");
                if(!target.equals(account())){startAccount(target);finish();return;}
            }
            String wanted=(account().equals("B")?"en-US/America/New_York":"zh-CN/Asia/Shanghai");
            if(runtime==null){
                String lang=account().equals("B")?"en-US":"zh-CN",zone=account().equals("B")?"America/New_York":"Asia/Shanghai";
                Locale locale=Locale.forLanguageTag(lang);Locale.setDefault(locale);LocaleList.setDefault(new LocaleList(locale));TimeZone.setDefault(TimeZone.getTimeZone(zone));Os.setenv("TZ",zone,true);
                File profile=new File(getFilesDir(),account().equals("B")?"managed-poc-b":"managed-poc-a");if(!profile.isDirectory()&&!profile.mkdirs())throw new IllegalStateException("Cannot create independent profile");
                Bundle env=new Bundle();env.putString("env0","TZ="+zone);
                runtime=GeckoRuntime.create(getApplicationContext(),new GeckoRuntimeSettings.Builder().arguments(new String[]{"-profile",profile.getAbsolutePath()}).extras(env).locales(new String[]{lang}).remoteDebuggingEnabled(false).consoleOutput(true).build());environment=wanted;
                controller=new ManagedPageController(runtime,store,owner->{ManagedRecoveryActivity host=hosts.get(owner);if(host!=null)host.refresh();},entry->{ManagedRecoveryActivity host=hosts.get(entry.account);return host!=null&&host.current()==entry&&host.web.getSession()==entry.session?host.web:null;});
            }
            if(!wanted.equals(environment))throw new IllegalStateException("Environment mismatch");
            controller.inputDiagnostics=getIntent().getBooleanExtra("diagnoseInput",false);openTabs();
            creating=false;show();command(getIntent());
        }catch(Exception error){creating=false;startupError=error.toString();refresh();record("startup-error");}
    }
    private void openTabs()throws Exception{
        if(tabs().isEmpty()){
            JSONObject workspace=store.read(account(),"workspace");int count=workspace==null?getIntent().getIntExtra("tabs",1):workspace.getInt("count"),index=workspace==null?0:workspace.getInt("selected");
            if(count<1||count>2||index<0||index>=count)throw new IllegalArgumentException("Invalid workspace");selected.put(account(),index);
            for(int i=0;i<count;i++)tabs().add(controller.open(account(),i));
        }
    }
    private TextView label(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.BLACK);v.setPadding(10,8,10,8);return v;}
    private void button(LinearLayout row,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextColor(Color.BLACK);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->action.run());}
    private void startAccount(String account){Class<?> cls=account.equals("B")?ManagedBActivity.class:account.equals("C")?ManagedCActivity.class:ManagedAActivity.class;startActivity(new Intent(this,cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("account",account));}
    private void workspace()throws Exception{store.write(account(),"workspace",new JSONObject().put("count",tabs().size()).put("selected",selected.getOrDefault(account(),0)));}
    private void release(){if(web!=null&&web.getSession()!=null){GeckoSession old=web.releaseSession();if(old!=null&&old.isOpen())old.setActive(true);}}
    private void show(){
        ManagedPageController.Entry e=current();if(e==null)return;
        for(ManagedPageController.Entry old:tabs())controller.visible(old,false);release();if(!e.blocked){controller.openWindow(e);web.setSession(e.session);e.session.setActive(true);web.requestFocus();}
        controller.visible(e,web.hasWindowFocus());try{workspace();}catch(Exception error){startupError=error.toString();}refresh();ui.postDelayed(()->record("layout"),500);
    }
    private void select(int index){if(index<0||index>=tabs().size())return;if(selected.getOrDefault(account(),0)==index&&web.getSession()==tabs().get(index).session){controller.visible(tabs().get(index),web.hasWindowFocus());refresh();return;}selected.put(account(),index);show();}
    void refresh(){
        if(creating||status==null||hosts.get(account())!=this)return;ManagedPageController.Entry e=current();
        if(!startupError.isEmpty()){status.setText("独立实验无法启动");preview.setText(startupError);overlay.setVisibility(View.VISIBLE);record("changed");return;}
        if(e==null)return;
        String title="账号 "+account()+" · 标签 "+(selected.getOrDefault(account(),0)+1)+"/"+tabs().size()+" · ";
        boolean ready="ready".equals(e.state);String statusText=title+(ready?"网页已恢复":"conflict".equals(e.state)?"检测到另一份草稿":"restoring".equals(e.state)?"正在恢复草稿":"上次保存的对话");if(!status.getText().toString().equals(statusText))status.setText(statusText);
        overlay.setVisibility(ready?View.GONE:View.VISIBLE);conflictControls.setVisibility("conflict".equals(e.state)?View.VISIBLE:View.GONE);retry.setEnabled(!e.blocked);retry.setVisibility("conflict".equals(e.state)?View.GONE:View.VISIBLE);
        web.setImportantForAccessibility(ready?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        overlay.setClickable(!ready);
        if(ready){record("changed");return;}
        String text="正在连接网页…";
        try{
            if(e.conflict!=null){text="两份草稿均已保留，请选择。\n\n上次草稿\n"+e.conflict.getJSONObject("saved").getString("standard")+"\n"+e.conflict.getJSONObject("saved").getString("rich")+"\n\n网页现有草稿\n"+e.current.getString("standard")+"\n"+e.current.getString("rich");}
            else if(e.preview!=null){text="上次保存的对话（只读）\n\n"+e.preview.getString("transcript")+"\n\n未发送草稿\n"+e.preview.getString("standard")+"\n"+e.preview.getString("rich");}
            if(e.blocked)text="恢复记录不可读，原文件已保留。\n"+e.error;
            else if(!e.error.isEmpty())text+="\n\n恢复尚未完成；本地记录保留。\n"+e.error;
        }catch(Exception error){text="恢复记录读取失败，原文件保留。";}
        if(!preview.getText().toString().equals(text))preview.setText(text);record("changed");
    }
    private void choose(boolean saved){try{if(current()!=null){web.requestFocus();controller.choose(current(),saved);refresh();}}catch(Exception error){startupError=error.toString();refresh();}}
    private void backups(){try{ManagedPageController.Entry e=current();if(e==null)return;JSONArray a=e.record.getJSONArray("alternatives");StringBuilder text=new StringBuilder();for(int i=0;i<a.length();i++){JSONObject item=a.getJSONObject(i);text.append("上次草稿\n").append(item.getJSONObject("saved").getString("standard")).append('\n').append(item.getJSONObject("saved").getString("rich")).append("\n\n网页草稿\n").append(item.getJSONObject("current").getString("standard")).append('\n').append(item.getJSONObject("current").getString("rich")).append("\n\n");}new AlertDialog.Builder(this).setTitle("保留的草稿").setMessage(text.length()==0?"暂无保留草稿":text.toString()).setPositiveButton("关闭",null).show();}catch(Exception error){startupError=error.toString();refresh();}}
    private void checkpoint(boolean close){
        String request=UUID.randomUUID().toString();List<ManagedPageController.Entry> active=controller.all();long started=SystemClock.elapsedRealtime();
        try{workspace();for(ManagedPageController.Entry e:active)controller.checkpoint(e,request);}catch(Exception error){record("checkpoint-unavailable");return;}
        ui.post(new Runnable(){public void run(){boolean done=active.stream().allMatch(e->request.equals(e.checkpoint));if(!done&&SystemClock.elapsedRealtime()-started<10000){ui.postDelayed(this,100);return;}
            record(done?"checkpoint-saved":"checkpoint-timeout");if(done&&close){
                for(ManagedRecoveryActivity host:new ArrayList<>(hosts.values()))host.release();controller.closeAll();groups.clear();
                for(ManagedRecoveryActivity host:new ArrayList<>(hosts.values()))host.finishAndRemoveTask();
                record("closed");
                // This control means an explicit complete exit of the lab app.
                // Ordinary Activity destruction above retains process-owned tabs.
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }});
    }
    private void sample(String encoded,boolean replace){
        try{ManagedPageController.Entry e=current();if(e==null)return;String value=new String(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT),StandardCharsets.UTF_8);if(value.length()>4000||!value.startsWith("demo-"+account()+"-"+e.id+"-"))throw new IllegalArgumentException("Invalid synthetic input");
            long started=SystemClock.elapsedRealtime();ui.post(new Runnable(){public void run(){if(current()!=e||!web.hasWindowFocus()||!web.hasFocus()){if(SystemClock.elapsedRealtime()-started<5000){ui.postDelayed(this,100);return;}record("sample-input-unavailable");return;}
                InputConnection input=web.onCreateInputConnection(new EditorInfo());if(input==null){if(SystemClock.elapsedRealtime()-started<5000){ui.postDelayed(this,100);return;}record("sample-input-unavailable");return;}
                Handler h=input.getHandler();if(h==null)h=ui;h.post(()->{boolean result=false;try{if(replace){android.view.inputmethod.ExtractedText text=input.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(),0);if(text==null||text.text==null)throw new IllegalStateException("Editor text unavailable");input.setSelection(0,text.text.length());}result=input.commitText(value,1);}catch(Exception ignored){}boolean accepted=result;ui.post(()->record(accepted?"sample-input-accepted":"sample-input-rejected"));});
            }});
        }catch(Exception error){startupError=error.toString();refresh();}
    }
    private void command(Intent intent){
        if(controller==null)return;String action=intent.getStringExtra("action");ManagedPageController.Entry e=current();
        try{
            if("select".equals(action))select(intent.getIntExtra("index",0));
            if("input".equals(action))sample(intent.getStringExtra("sample"),intent.getBooleanExtra("replace",false));
            if("checkpoint".equals(action))checkpoint(false);if("quit".equals(action))checkpoint(true);
            if("retry".equals(action)&&e!=null)controller.retry(e);
            if("keep".equals(action))choose(false);if("restore".equals(action))choose(true);if("backups".equals(action))backups();
            if("back".equals(action)&&e!=null)controller.history(e,false);if("forward".equals(action)&&e!=null)controller.history(e,true);
            if("recreate".equals(action)){intent.removeExtra("action");recreate();}
            if("scrollSample".equals(action)&&e!=null)e.session.getPanZoomController().scrollTo(org.mozilla.geckoview.ScreenLength.fromPixels(0),org.mozilla.geckoview.ScreenLength.fromPixels(1400));
            if("scrollTop".equals(action)&&e!=null)e.session.getPanZoomController().scrollToTop();
            if("hideKeyboard".equals(action))((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(web.getWindowToken(),0);
        }catch(Exception error){startupError=error.toString();refresh();}
        ui.postDelayed(()->record("inspected"),500);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);try{boolean empty=tabs().isEmpty();openTabs();if(empty)show();command(intent);}catch(Exception error){startupError=error.toString();refresh();}}
    @Override protected void onResume(){super.onResume();if(hosts.get(account())!=this)return;if(web!=null){web.requestFocus();if(current()!=null&&controller!=null){if(current().session.isOpen()){current().session.setActive(true);if(web.hasWindowFocus())current().session.setFocused(true);}controller.visible(current(),web.hasWindowFocus());}ui.postDelayed(()->record("resumed"),500);}}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(hosts.get(account())!=this)return;if(controller!=null&&current()!=null){if(current().session.isOpen()&&focused)current().session.setFocused(true);controller.visible(current(),focused);if(focused)try{store.write("A","index",new JSONObject().put("activeAccount",account()));}catch(Exception error){startupError=error.toString();}record("window-focus");}}
    private static void retainSessions(){if(controller!=null)for(ManagedPageController.Entry e:controller.all())if(e.session.isOpen())e.session.setActive(true);}
    @Override protected void onStop(){super.onStop();record("stopped");retainSessions();ui.postDelayed(ManagedRecoveryActivity::retainSessions,500);ui.postDelayed(ManagedRecoveryActivity::retainSessions,1500);}
    @Override protected void onDestroy(){android.util.Log.i("ManagedRecoveryPoC","lifecycle=destroy account="+account()+" owned="+(hosts.get(account())==this));if(hosts.get(account())==this&&controller!=null&&current()!=null)controller.visible(current(),false);release();if(hosts.get(account())==this)hosts.remove(account());super.onDestroy();}
    private void record(String event){if(hosts.get(account())!=this)return;try{
        if(event.equals("resumed")||event.equals("stopped")||event.equals("window-focus")||event.equals("checkpoint-saved")||event.equals("closed")||event.equals("startup-error"))android.util.Log.i("ManagedRecoveryPoC","event="+event+" account="+account()+" selected="+selected.getOrDefault(account(),0));
        JSONArray entries=new JSONArray();if(controller!=null)for(ManagedPageController.Entry e:tabs())entries.put(controller.metadata(e));JSONObject row=new JSONObject().put("event",event).put("account",account()).put("pid",android.os.Process.myPid()).put("selected",selected.getOrDefault(account(),0)).put("entries",entries).put("startupError",startupError).put("bridgeError",controller==null?"":controller.startupError).put("elapsedMs",SystemClock.elapsedRealtime());
        if(controller!=null)row.put("workCounters",controller.workCounters());
        Runtime memory=Runtime.getRuntime();row.put("heapUsage",new JSONObject().put("javaUsedBytes",memory.totalMemory()-memory.freeMemory()).put("javaCommittedBytes",memory.totalMemory()).put("nativeAllocatedBytes",android.os.Debug.getNativeHeapAllocatedSize()));
        if(web!=null){int[] xy=new int[2];web.getLocationOnScreen(xy);row.put("viewX",xy[0]).put("viewY",xy[1]).put("viewWidth",web.getWidth()).put("viewHeight",web.getHeight()).put("windowFocused",web.hasWindowFocus()).put("viewShown",web.isShown());}
        byte[] bytes=row.toString().getBytes(StandardCharsets.UTF_8);
        // This is lab observation metadata, not product state. Publish a complete
        // snapshot so an external reader cannot see a truncated JSON mid-write.
        File latest=new File(getFilesDir(),"managed-native-"+account()+".json"),temporary=new File(getFilesDir(),"managed-native-"+account()+".json.tmp");
        Files.write(temporary.toPath(),bytes);Os.rename(temporary.getAbsolutePath(),latest.getAbsolutePath());
        try(FileOutputStream log=new FileOutputStream(new File(getFilesDir(),"managed-events-"+account()+".jsonl"),true)){log.write(bytes);log.write('\n');}
    }catch(Exception error){android.util.Log.e("ManagedRecoveryPoC","Evidence failed",error);}}
}
