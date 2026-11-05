package com.forcemind.chatgpt.pocinput;

import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CI shell only. One paced native keyboard batch, with no app or page test bridge. */
public final class AsciiInput {
    private static final int PACE_MILLIS=40,WAIT_FOR_FINISH=2;
    private static String hash(String value)throws Exception{
        StringBuilder result=new StringBuilder();
        for(byte item:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))result.append(String.format(Locale.ROOT,"%02x",item&255));
        return result.toString();
    }
    private static void receipt(boolean complete,String value,List<String> events,String error)throws Exception{
        System.out.println("{\"provider\":\"ci-paced-ascii-keyboard\",\"complete\":"+complete+",\"api\":"+Build.VERSION.SDK_INT+",\"uid\":"+android.os.Process.myUid()+",\"qemu\":true,\"textLength\":"+value.length()+",\"textSHA256\":\""+hash(value)+"\",\"paceMillis\":"+PACE_MILLIS+",\"injectionMode\":"+WAIT_FOR_FINISH+",\"errorClass\":\""+error+"\",\"events\":["+String.join(",",events)+"]}");
    }
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=2000||(Build.VERSION.SDK_INT!=28&&Build.VERSION.SDK_INT!=35)||args.length!=1)throw new IllegalStateException("Owned API28/API35 CI shell only");
        Process check=new ProcessBuilder("/system/bin/getprop","ro.kernel.qemu").start();String qemu;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(check.getInputStream()))){qemu=reader.readLine();}
        if(check.waitFor()!=0||!"1".equals(qemu))throw new IllegalStateException("Physical devices refused");
        String value=args[0];if(!value.matches("[A-Za-z0-9-]{1,128}"))throw new IllegalArgumentException("Restricted owned ASCII fixture only");
        KeyEvent[] planned=KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray());
        if(planned==null)throw new IllegalStateException("Virtual keyboard cannot represent fixture");
        StringBuilder printable=new StringBuilder();
        for(KeyEvent event:planned){
            if(event.getAction()!=KeyEvent.ACTION_DOWN&&event.getAction()!=KeyEvent.ACTION_UP)throw new IllegalStateException("Unexpected keyboard action");
            int code=event.getUnicodeChar();if(event.getAction()==KeyEvent.ACTION_DOWN&&code!=0)printable.appendCodePoint(code);
        }
        if(!value.equals(printable.toString()))throw new IllegalStateException("Planned native character sequence differs");
        Class<?> type=Class.forName("android.hardware.input.InputManager");Object manager=type.getMethod("getInstance").invoke(null);
        Method inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        Map<Integer,Long> downTimes=new HashMap<>();List<String> events=new ArrayList<>();
        try{
            for(int index=0;index<planned.length;index++){
                KeyEvent source=planned[index];int action=source.getAction(),key=source.getKeyCode();long now=SystemClock.uptimeMillis(),down;
                if(action==KeyEvent.ACTION_DOWN){if(downTimes.containsKey(key))throw new IllegalStateException("Duplicate native key down");down=now;downTimes.put(key,down);}
                else{Long started=downTimes.remove(key);if(started==null)throw new IllegalStateException("Native key up has no down");down=started;}
                KeyEvent event=new KeyEvent(down,now,action,key,0,source.getMetaState(),KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD);
                boolean accepted=Boolean.TRUE.equals(inject.invoke(manager,event,WAIT_FOR_FINISH));long completed=SystemClock.uptimeMillis();
                events.add("{\"index\":"+index+",\"action\":"+action+",\"keyCode\":"+key+",\"unicodeChar\":"+event.getUnicodeChar()+",\"metaState\":"+event.getMetaState()+",\"repeatCount\":"+event.getRepeatCount()+",\"downTime\":"+event.getDownTime()+",\"eventTime\":"+event.getEventTime()+",\"completedAt\":"+completed+",\"source\":"+event.getSource()+",\"deviceId\":"+event.getDeviceId()+",\"waitForFinish\":true,\"accepted\":"+accepted+"}");
                if(!accepted)throw new IllegalStateException("System keyboard input refused");
                SystemClock.sleep(PACE_MILLIS);
            }
            if(!downTimes.isEmpty())throw new IllegalStateException("Native keyboard batch left keys down");
            receipt(true,value,events,"");
        }catch(Exception error){receipt(false,value,events,error.getClass().getName());throw error;}
    }
}
