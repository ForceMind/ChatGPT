package com.forcemind.chatgpt.profiles;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Visible, user-requested browser task mode. Does not restart after process death. */
public final class KeepaliveService extends Service {
    private static final String CHANNEL="browser-tasks",STOP="stop-browser-tasks";
    private static boolean running;
    static boolean enabled(Context context){return context.getSharedPreferences("browser-preferences",0).getBoolean("backgroundTasks",false);}
    static boolean running(){return running;}
    static void enable(Context context){context.getSharedPreferences("browser-preferences",0).edit().putBoolean("backgroundTasks",true).apply();context.startForegroundService(new Intent(context,KeepaliveService.class));}
    static void disable(Context context){context.getSharedPreferences("browser-preferences",0).edit().putBoolean("backgroundTasks",false).apply();context.stopService(new Intent(context,KeepaliveService.class));}
    @Override public void onCreate(){super.onCreate();NotificationChannel channel=new NotificationChannel(CHANNEL,"浏览器后台任务",NotificationManager.IMPORTANCE_LOW);channel.setDescription("仅在主动开启后台任务模式后显示");channel.setShowBadge(false);getSystemService(NotificationManager.class).createNotificationChannel(channel);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){disable(this);return START_NOT_STICKY;}
        try{
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,KeepaliveService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("ChatGPT Studio · 后台任务运行中").setContentText("保留已打开的网页；点按返回，或停止后台模式").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,"停止后台模式",stop).build()).build();
            if(Build.VERSION.SDK_INT>=34)startForeground(2417,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(2417,notification);
            running=true;BrowserMemory.get(this).diagnostics.event("background-start",null,null,null);
        }catch(RuntimeException error){getSharedPreferences("browser-preferences",0).edit().putBoolean("backgroundTasks",false).apply();BrowserMemory.get(this).diagnostics.event("background-error",null,null,error.getClass().getSimpleName());stopSelf();}
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){if(running)BrowserMemory.get(this).diagnostics.event("background-stop",null,null,null);running=false;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
