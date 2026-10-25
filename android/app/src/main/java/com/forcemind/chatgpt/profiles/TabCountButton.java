package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.graphics.*;
import android.view.Gravity;
import android.widget.TextView;

final class TabCountButton extends TextView {
    private final Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);
    TabCountButton(Context context){super(context);setGravity(Gravity.CENTER);setTextColor(Color.rgb(23,23,23));setClickable(true);setFocusable(true);border.setColor(Color.rgb(23,23,23));border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(1.6f));}
    @Override protected void onDraw(Canvas canvas){float w=dp(29),h=dp(28),x=(getWidth()-w)/2f,y=(getHeight()-h)/2f;canvas.drawRoundRect(x,y,x+w,y+h,dp(6),dp(6),border);super.onDraw(canvas);}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
