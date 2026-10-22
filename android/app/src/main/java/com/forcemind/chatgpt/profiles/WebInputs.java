package com.forcemind.chatgpt.profiles;

import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.*;
import android.webkit.WebView;
import java.io.IOException;

/** Public input APIs scoped to an already validated, visible WebView. */
final class WebInputs {
    @SuppressWarnings("deprecation")
    static void tap(WebView web,double cssX,double cssY)throws IOException{
        if(!web.isShown()||web.getWindowToken()==null||!Double.isFinite(cssX)||!Double.isFinite(cssY))throw new IOException("目标网页不可操作");
        float x=(float)(cssX*web.getScale()),y=(float)(cssY*web.getScale());
        if(x<0||y<0||x>=web.getWidth()||y>=web.getHeight())throw new IOException("网页上传控件不在可见区域");
        web.requestFocus();long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{if(!web.dispatchTouchEvent(down))throw new IOException("网页未接收上传操作");}finally{down.recycle();}
        MotionEvent up=MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{if(!web.dispatchTouchEvent(up))throw new IOException("网页未接收上传操作");}finally{up.recycle();}
    }
    static boolean insert(WebView web,String text){
        if(!web.isShown()||web.getWindowToken()==null)return false;web.requestFocus();
        InputConnection input=web.onCreateInputConnection(new EditorInfo());return input!=null&&input.commitText(text,1);
    }
}
