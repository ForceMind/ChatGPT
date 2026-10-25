package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.view.View;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.webkit.WebView;

/** Keep page scheduling active while Studio is foreground or its visible task service is running. */
final class RetainedWebView extends WebView {
    private boolean executionRetained,selected,inputBlocked;
    RetainedWebView(Context context){super(context);}
    void retainExecution(boolean retain){
        if(executionRetained==retain)return;executionRetained=retain;
        updatePresentation();
        super.onWindowVisibilityChanged(retain?View.VISIBLE:getWindowVisibility());
    }
    void selectPage(boolean value){if(selected==value)return;selected=value;updatePresentation();}
    void blockInput(boolean value){if(inputBlocked==value)return;inputBlocked=value;updatePresentation();}
    private void updatePresentation(){
        // AwContents reads the real View visibility, not the callback argument.
        // Inactive retained pages stay attached and transparent, without input/accessibility.
        setAlpha(selected?1f:0f);setEnabled(selected&&!inputBlocked);setFocusable(selected&&!inputBlocked);setFocusableInTouchMode(selected&&!inputBlocked);
        setImportantForAccessibility(selected&&!inputBlocked?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        super.setVisibility(selected||executionRetained?View.VISIBLE:View.INVISIBLE);
    }
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(executionRetained?View.VISIBLE:visibility);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){return selected&&!inputBlocked&&super.dispatchTouchEvent(event);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){return selected&&!inputBlocked&&super.dispatchGenericMotionEvent(event);}
    @Override public boolean dispatchKeyEvent(KeyEvent event){return selected&&!inputBlocked&&super.dispatchKeyEvent(event);}
}
