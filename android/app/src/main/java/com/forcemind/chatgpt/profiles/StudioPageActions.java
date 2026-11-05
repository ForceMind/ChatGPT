package com.forcemind.chatgpt.profiles;

import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

/** Navigation is the first visible row of More, with actual disabled controls. */
final class StudioPageActions {
    static void navigation(StudioSheet sheet,boolean canBack,boolean canForward,Runnable back,Runnable forward){
        LinearLayout row=new LinearLayout(sheet.getContext());row.setGravity(Gravity.CENTER_VERTICAL);
        Button previous=sheet.button("后退",false),next=sheet.button("前进",false);previous.setContentDescription("后退");next.setContentDescription("前进");previous.setEnabled(canBack);next.setEnabled(canForward);previous.setOnClickListener(v->{sheet.dismiss();back.run();});next.setOnClickListener(v->{sheet.dismiss();forward.run();});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=StudioTypography.dp(sheet.getContext(),8);row.addView(previous,p);row.addView(next,new LinearLayout.LayoutParams(0,-2,1));sheet.body.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    static LinearLayout action(StudioSheet sheet,String label,String detail,String icon,boolean enabled,Runnable action){
        LinearLayout row=sheet.row(label,detail,icon,()->{if(enabled)action.run();});row.setEnabled(enabled);row.setClickable(enabled);row.setFocusable(enabled);row.setAlpha(enabled?1f:.45f);return row;
    }
    private StudioPageActions(){}
}
