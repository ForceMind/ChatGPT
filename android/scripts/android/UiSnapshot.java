package com.forcemind.chatgpt.pocinput;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.json.JSONArray;
import org.xmlpull.v1.XmlSerializer;

/** Owned emulator shell only. One new connection and an input-focused application root, no actions. */
public final class UiSnapshot {
    private static volatile String TARGET="com.forcemind.chatgpt.profiles.networkbeta";
    private static final AtomicBoolean EMITTED=new AtomicBoolean();
    private static volatile boolean qemu;
    private static volatile String request="",phase="guard";
    private static volatile long started=SystemClock.elapsedRealtime(),captured;
    private static volatile int count,window=-1;
    private static volatile int serviceFlags;
    private static volatile boolean windowsComplete,packagesComplete;
    private static volatile String windowsJson="[]",activeJson="null",chosenJson="null";
    private static volatile String rootPackage="";
    private static volatile int windowQueries,lastWindowCount=-1;
    private static volatile String errorMessage="";
    private static long deadline,readDeadline;
    private static volatile boolean cleanupAttempted,cleanupCompleted;
    private static volatile String cleanupErrorClass="";
    private static void check()throws IOException{
        if(SystemClock.elapsedRealtime()>=readDeadline)throw new IOException("AX read deadline expired; cleanup reserve retained");
    }
    private static String sha(String value)throws Exception{
        StringBuilder output=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))output.append(String.format(Locale.ROOT,"%02x",b&255));
        return output.toString();
    }
    private static void finish(boolean complete,String xml,String error,int code){
        if(!EMITTED.compareAndSet(false,true))return;
        try{
            JSONObject value=new JSONObject().put("schema",1).put("provider","ci-readonly-active-window-ax")
                .put("complete",complete).put("available",complete).put("api",Build.VERSION.SDK_INT)
                .put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid()).put("qemu",qemu)
                .put("package",TARGET).put("requestId",request).put("phase",phase).put("windowId",window)
                .put("selection","unique-input-focused-application").put("rootWindowId",window)
                .put("rootPackage",rootPackage).put("windows",new JSONArray(windowsJson))
                .put("activeRoot",activeJson.equals("null")?JSONObject.NULL:new JSONObject(activeJson))
                .put("chosenWindow",chosenJson.equals("null")?JSONObject.NULL:new JSONObject(chosenJson))
                .put("windowsMetadataComplete",windowsComplete).put("windowPackagesComplete",packagesComplete)
                .put("serviceFlags",serviceFlags)
                .put("startedElapsedMillis",started).put("capturedElapsedMillis",captured)
                .put("readDeadlineElapsedMillis",readDeadline).put("hardDeadlineElapsedMillis",deadline)
                .put("cleanupAttempted",cleanupAttempted).put("cleanupCompleted",cleanupCompleted)
                .put("cleanupErrorClass",cleanupErrorClass)
                .put("finishedElapsedMillis",SystemClock.elapsedRealtime()).put("nodeCount",count)
                .put("waitForIdle",false).put("actionsPerformed",0).put("screenshots",false)
                .put("connectionClass","android.app.UiAutomationConnection")
                .put("constructorSignature","UiAutomation(Looper,IUiAutomationConnection)")
                .put("connectionFlags",UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .put("errorClass",error).put("errorMessage",errorMessage)
                .put("windowQueries",windowQueries).put("lastWindowCount",lastWindowCount);
            if(complete)value.put("xml",xml).put("xmlSHA256",sha(xml));
            System.out.println(value.toString());System.out.flush();
        }catch(Throwable failure){System.err.println("AX receipt unavailable: "+failure.getClass().getName());}
        System.exit(code);
    }
    private static String text(CharSequence value)throws IOException{
        String result=value==null?"":value.toString();
        for(int offset=0;offset<result.length();){
            int cp=result.codePointAt(offset);
            if(!(cp==9||cp==10||cp==13||cp>=32&&cp<=0xd7ff||cp>=0xe000&&cp<=0xfffd||cp>=0x10000&&cp<=0x10ffff))throw new IOException("Invalid XML character");
            offset+=Character.charCount(cp);
        }
        return result;
    }
    private static void attribute(XmlSerializer xml,String name,CharSequence value)throws IOException{xml.attribute("",name,text(value));}
    private static void flag(XmlSerializer xml,String name,boolean value)throws IOException{attribute(xml,name,Boolean.toString(value));}
    private static void node(XmlSerializer xml,AccessibilityNodeInfo item,int index,int depth)throws Exception{
        check();if(depth>64||++count>8192)throw new IOException("Owned AX tree limit exceeded");
        if(!TARGET.contentEquals(item.getPackageName()==null?"":item.getPackageName()))throw new SecurityException("Non-owned AX node refused");
        Rect bounds=new Rect();item.getBoundsInScreen(bounds);
        xml.startTag("","node");attribute(xml,"index",Integer.toString(index));attribute(xml,"text",item.isPassword()?"":item.getText());
        attribute(xml,"resource-id",item.getViewIdResourceName());attribute(xml,"class",item.getClassName());
        attribute(xml,"package",item.getPackageName());attribute(xml,"content-desc",item.getContentDescription());
        attribute(xml,"window-id",Integer.toString(item.getWindowId()));
        flag(xml,"checkable",item.isCheckable());flag(xml,"checked",item.isChecked());flag(xml,"clickable",item.isClickable());
        flag(xml,"enabled",item.isEnabled());flag(xml,"focusable",item.isFocusable());flag(xml,"focused",item.isFocused());
        flag(xml,"scrollable",item.isScrollable());flag(xml,"long-clickable",item.isLongClickable());flag(xml,"password",item.isPassword());
        flag(xml,"selected",item.isSelected());flag(xml,"visible-to-user",item.isVisibleToUser());flag(xml,"editable",item.isEditable());
        attribute(xml,"hint",item.getHintText());attribute(xml,"bounds","["+bounds.left+","+bounds.top+"]["+bounds.right+","+bounds.bottom+"]");
        for(int i=0;i<item.getChildCount();i++){
            check();AccessibilityNodeInfo child=item.getChild(i);
            if(child==null)throw new IOException("AX child became unavailable");
            try{if(child.isVisibleToUser())node(xml,child,i,depth+1);}finally{child.recycle();}
        }
        xml.endTag("","node");check();
    }
    public static void main(final String[] args){
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler(){
            @Override public void uncaughtException(Thread thread,Throwable error){
                finish(false,null,error.getClass().getName(),1);
            }
        });
        try{
            // API35's AccessibilityInteractionClient creates a Handler on the process main
            // Looper, even when UiAutomation uses its separate callback HandlerThread.
            // Keep that queue running; the read worker and watchdog retain the same deadline.
            if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
            if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Owned AX main looper unavailable");
            new Thread(new Runnable(){@Override public void run(){capture(args);}},"OwnedAxRead").start();
            Looper.loop();
            finish(false,null,IllegalStateException.class.getName(),1);
        }catch(Throwable failure){finish(false,null,failure.getClass().getName(),1);}
    }
    private static void capture(String[] args){
        if(args.length!=3){finish(false,null,IllegalArgumentException.class.getName(),1);return;}
        if(!"com.forcemind.chatgpt.profiles.networkbeta".equals(args[0])&&!"com.forcemind.chatgpt.profiles".equals(args[0])){
            finish(false,null,SecurityException.class.getName(),1);return;
        }
        TARGET=args[0];
        long budget;
        try{
            request=UUID.fromString(args[1]).toString();if(!request.equals(args[1]))throw new IllegalArgumentException();
            budget=Long.parseLong(args[2]);if(budget<1||budget>25000)throw new IllegalArgumentException();
        }catch(Exception invalid){finish(false,null,invalid.getClass().getName(),1);return;}
        deadline=started+budget;
        // Leave time for normal disconnect before the watchdog can exit the
        // process. Exiting during disconnect left Android 9's service registered.
        readDeadline=deadline-Math.min(750,Math.max(1,budget/4));
        Thread watchdog=new Thread(new Runnable(){@Override public void run(){
            while(!EMITTED.get()){
                long remaining=deadline-SystemClock.elapsedRealtime();
                if(remaining<=0){finish(false,null,java.util.concurrent.TimeoutException.class.getName(),124);return;}
                SystemClock.sleep(remaining);
            }
        }},"OwnedAxDeadline");watchdog.setDaemon(true);watchdog.start();
        UiAutomation automation=null;HandlerThread callbacks=null;AccessibilityNodeInfo root=null;
        List<AccessibilityWindowInfo> windows=null;
        String xml=null,error="",failedPhase="";
        try{
            int uid=android.os.Process.myUid();
            if(!"true".equals(System.getenv("GITHUB_ACTIONS"))||(uid!=2000&&uid!=0)
                    ||(Build.VERSION.SDK_INT!=28&&Build.VERSION.SDK_INT!=35)||!TARGET.equals(args[0]))throw new SecurityException("Owned emulator CI only");
            Process property=new ProcessBuilder("/system/bin/getprop","ro.kernel.qemu").start();
            if(!property.waitFor(Math.min(1000,Math.max(1,deadline-SystemClock.elapsedRealtime())),TimeUnit.MILLISECONDS)){
                property.destroyForcibly();throw new IOException("Emulator guard unavailable");
            }
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(property.getInputStream(),StandardCharsets.UTF_8))){qemu=property.exitValue()==0&&"1".equals(reader.readLine());}
            if(!qemu)throw new SecurityException("Physical device refused");check();phase="connect";
            callbacks=new HandlerThread("OwnedAxCallbacks");callbacks.start();
            // Fixed AOSP android-9.0.0_r1 and android-15.0.0_r1 both expose this exact
            // hidden constructor and connect(int)/disconnect(). No signature guessing.
            Class<?> connectionType=Class.forName("android.app.IUiAutomationConnection");
            Object connection=Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,connectionType).newInstance(callbacks.getLooper(),connection);
            UiAutomation.class.getMethod("connect",int.class).invoke(automation,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            check();AccessibilityServiceInfo info=automation.getServiceInfo();if(info==null)throw new IOException("AX service unavailable");
            info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                |AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.packageNames=new String[]{TARGET};automation.setServiceInfo(info);
            AccessibilityServiceInfo observed=automation.getServiceInfo();if(observed==null)throw new IOException("AX service flags unavailable");
            serviceFlags=observed.flags;
            if((serviceFlags&AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)==0)throw new IOException("Interactive AX windows unavailable");
            phase="windows";
            // Window tracking begins asynchronously after the service flags are
            // installed. Wait within this same read deadline, without reconnecting.
            while(true){
                check();windows=automation.getWindows();windowQueries++;lastWindowCount=windows==null?-1:windows.size();check();
                if(windows!=null&&!windows.isEmpty())break;
                SystemClock.sleep(Math.min(40,Math.max(1,deadline-SystemClock.elapsedRealtime())));
            }
            if(windows.size()>64)throw new IOException("AX window list limit exceeded");
            JSONArray metadata=new JSONArray();int focusedApplications=0;
            for(AccessibilityWindowInfo item:windows){
                check();metadata.put(new JSONObject().put("id",item.getId()).put("type",item.getType())
                    .put("isFocused",item.isFocused()).put("isActive",item.isActive())
                    .put("allowedPackage","").put("packageQueried",false).put("rootPresent",false));
                if(item.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&item.isFocused())focusedApplications++;
            }
            windowsJson=metadata.toString();windowsComplete=true;
            // Retain active-window facts, but never use expected account labels to choose a root.
            phase="active-root";AccessibilityNodeInfo active=automation.getRootInActiveWindow();check();
            try{activeJson=new JSONObject().put("id",active==null?-1:active.getWindowId()).put("rootPresent",active!=null)
                .put("allowedPackage",active!=null&&TARGET.contentEquals(active.getPackageName()==null?"":active.getPackageName())?TARGET:"").toString();}
            finally{if(active!=null)active.recycle();}
            phase="focused-root";
            for(int i=0;i<windows.size();i++){
                check();AccessibilityWindowInfo item=windows.get(i);AccessibilityNodeInfo candidate=item.getRoot();check();
                try{
                    boolean allowed=candidate!=null&&TARGET.contentEquals(candidate.getPackageName()==null?"":candidate.getPackageName());
                    JSONObject facts=metadata.getJSONObject(i);facts.put("packageQueried",true).put("rootPresent",candidate!=null)
                        .put("allowedPackage",allowed?TARGET:"");
                    if(candidate!=null)facts.put("rootWindowId",candidate.getWindowId());
                    windowsJson=metadata.toString();
                    if(item.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&item.isFocused()&&allowed){
                        if(root!=null||focusedApplications!=1)throw new IOException("Focused application AX window ambiguous");
                        if(candidate.getWindowId()!=item.getId())throw new IOException("Focused AX root window differs");
                        root=candidate;candidate=null;window=item.getId();rootPackage=TARGET;chosenJson=facts.toString();
                    }
                }finally{if(candidate!=null)candidate.recycle();}
            }
            packagesComplete=true;
            if(focusedApplications!=1||root==null||!root.isVisibleToUser())throw new IOException("Owned input-focused application root unavailable");
            captured=SystemClock.elapsedRealtime();phase="serialize";
            StringWriter buffer=new StringWriter();XmlSerializer serializer=Xml.newSerializer();serializer.setOutput(buffer);
            serializer.startDocument("UTF-8",true);serializer.startTag("","hierarchy");attribute(serializer,"window-id",Integer.toString(window));
            node(serializer,root,0,0);serializer.endTag("","hierarchy");serializer.endDocument();serializer.flush();
            xml=buffer.toString();if(xml.length()>4*1024*1024)throw new IOException("Owned AX XML limit exceeded");check();
        }catch(Throwable failure){
            Throwable cause=failure instanceof InvocationTargetException&&failure.getCause()!=null?failure.getCause():failure;
            error=cause.getClass().getName();errorMessage=String.valueOf(cause.getMessage());failedPhase=phase;xml=null;
        }finally{
            if(root!=null)root.recycle();
            if(windows!=null)for(AccessibilityWindowInfo item:windows)item.recycle();
            if(automation!=null){
                phase="disconnect";cleanupAttempted=true;
                try{UiAutomation.class.getMethod("disconnect").invoke(automation);cleanupCompleted=true;}
                catch(Throwable failure){cleanupErrorClass=failure.getClass().getName();if(error.isEmpty()){error=cleanupErrorClass;failedPhase="disconnect";}xml=null;}
            }
            if(callbacks!=null)callbacks.quitSafely();
        }
        phase=xml==null?failedPhase:"complete";finish(xml!=null,xml,error,xml==null?1:0);
    }
}
