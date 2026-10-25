package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.TextView;

/** Shared roles for native controls. SP always follows the user's system scale. */
final class StudioTypography {
    static final int INK=0xff202020,MUTED=0xff606060;
    static void text(TextView view,int sp){view.setTextSize(TypedValue.COMPLEX_UNIT_SP,Math.max(13,sp));}
    static void meta(TextView view){text(view,13);view.setTextColor(MUTED);}
    static void button(Button view){text(view,14);view.setAllCaps(false);view.setMinWidth(0);view.setMinHeight(controlHeight(view.getContext()));view.setPadding(dp(view.getContext(),12),dp(view.getContext(),8),dp(view.getContext(),12),dp(view.getContext(),8));view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,INK}));}
    static int controlHeight(Context context){return lineBox(context,14,1,20,48);}
    static int lineBox(Context context,int sp,int lines,int paddingDp,int minimumDp){float pixels=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,sp,context.getResources().getDisplayMetrics());return Math.max(dp(context,minimumDp),(int)Math.ceil(pixels*1.45f*lines)+dp(context,paddingDp));}
    static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private StudioTypography(){}
}
