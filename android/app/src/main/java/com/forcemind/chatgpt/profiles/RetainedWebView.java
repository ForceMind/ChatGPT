package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/** Keep page scheduling active while Studio is foreground or its visible task service is running. */
final class RetainedWebView extends WebView {
    private boolean executionRetained;
    RetainedWebView(Context context){super(context);}
    void retainExecution(boolean retain){
        if(executionRetained==retain)return;executionRetained=retain;
        // Drawing, touch, accessibility and focus still follow the actual Android view.
        // These provider callbacks control Chromium's hidden-page timer scheduling.
        super.onWindowVisibilityChanged(retain?View.VISIBLE:getWindowVisibility());
        super.onVisibilityChanged(this,retain?View.VISIBLE:getVisibility());
    }
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(executionRetained?View.VISIBLE:visibility);}
    @Override protected void onVisibilityChanged(View changedView,int visibility){super.onVisibilityChanged(changedView,executionRetained?View.VISIBLE:visibility);}
}
