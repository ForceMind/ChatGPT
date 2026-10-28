package com.forcemind.chatgpt.pocinput;

import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** CI-only shell tool; never packaged in the app. Fixes the legacy input
 * command's per-event downTime while retaining the real Android input path. */
public final class TouchInput {
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=2000||Build.VERSION.SDK_INT!=28||args.length!=5)throw new IllegalStateException("Android 9 CI shell only");
        Process check=new ProcessBuilder("/system/bin/getprop","ro.kernel.qemu").start();
        String qemu;try(BufferedReader r=new BufferedReader(new InputStreamReader(check.getInputStream()))){qemu=r.readLine();}
        if(check.waitFor()!=0||!"1".equals(qemu))throw new IllegalStateException("Physical devices refused");
        float x1=Float.parseFloat(args[0]),y1=Float.parseFloat(args[1]),x2=Float.parseFloat(args[2]),y2=Float.parseFloat(args[3]);int duration=Integer.parseInt(args[4]);
        for(float value:new float[]{x1,y1,x2,y2})if(!Float.isFinite(value)||value<0||value>4096)throw new IllegalArgumentException("Invalid CI coordinate");
        if(duration<150||duration>1500)throw new IllegalArgumentException("Invalid CI duration");
        Class<?> type=Class.forName("android.hardware.input.InputManager");Object manager=type.getMethod("getInstance").invoke(null);
        Method inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        int device=0;for(int id:InputDevice.getDeviceIds())if(InputDevice.getDevice(id).supportsSource(InputDevice.SOURCE_TOUCHSCREEN)){device=id;break;}
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();properties.id=0;properties.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.size=1;
        long down=SystemClock.uptimeMillis();List<String> receipts=new ArrayList<>();
        for(int i=0;i<=32;i++){
            long delay=down+duration*i/32-SystemClock.uptimeMillis();if(delay>0)SystemClock.sleep(delay);
            float fraction=i/32f;coords.x=x1+(x2-x1)*fraction;coords.y=y1+(y2-y1)*fraction;coords.pressure=i==32?0:1;
            int action=i==0?MotionEvent.ACTION_DOWN:i==32?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coords},0,0,1,1,device,0,InputDevice.SOURCE_TOUCHSCREEN,0);
            try{
                if(!Boolean.TRUE.equals(inject.invoke(manager,event,2)))throw new IllegalStateException("System input injection refused");
                receipts.add("{\"action\":"+event.getActionMasked()+",\"downTime\":"+event.getDownTime()+",\"eventTime\":"+event.getEventTime()+",\"source\":"+event.getSource()+",\"toolType\":"+event.getToolType(0)+"}");
            }finally{event.recycle();}
        }
        System.out.println("{\"provider\":\"ci-android9-shell-touchscreen\",\"events\":["+String.join(",",receipts)+"]}");
    }
}
