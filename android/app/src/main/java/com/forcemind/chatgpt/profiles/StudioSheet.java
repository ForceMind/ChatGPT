package com.forcemind.chatgpt.profiles;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** User-facing sheets share typography and keep long content above reachable actions. */
final class StudioSheet extends Dialog {
    final LinearLayout body,footer;
    private final Context context;
    StudioSheet(Context context,String title,String subtitle){
        super(context);this.context=context;requestWindowFeature(Window.FEATURE_NO_TITLE);setCanceledOnTouchOutside(true);
        LinearLayout root=new LinearLayout(context){
            @Override protected void onMeasure(int width,int height){
                int available=MeasureSpec.getSize(height),limit=Math.round(getResources().getDisplayMetrics().heightPixels*.9f);
                super.onMeasure(width,MeasureSpec.makeMeasureSpec(Math.min(available>0?available:limit,limit),MeasureSpec.AT_MOST));
            }
        };root.setOrientation(LinearLayout.VERTICAL);root.setBackground(shape(Color.WHITE,20));root.setPadding(0,0,0,dp(12));root.setClipToOutline(true);
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.TOP);header.setPadding(dp(20),dp(20),dp(12),dp(14));root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout titles=new LinearLayout(context);titles.setOrientation(LinearLayout.VERTICAL);TextView name=text(title,19);name.setTypeface(null,android.graphics.Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);titles.addView(name);if(!subtitle.isEmpty()){TextView detail=text(subtitle,13);StudioTypography.meta(detail);detail.setPadding(0,dp(5),0,0);titles.addView(detail);}header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button done=button("完成",false);done.setContentDescription("关闭弹层");done.setOnClickListener(v->dismiss());header.addView(done,new LinearLayout.LayoutParams(-2,-2));
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(false);body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),0,dp(20),dp(16));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,-2,1));
        footer=new LinearLayout(context);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(20),dp(10),dp(20),dp(6));root.addView(footer,new LinearLayout.LayoutParams(-1,-2));footer.setVisibility(View.GONE);setContentView(root);
        Window window=getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.24f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
    }
    @Override public void show(){super.show();Window window=getWindow();if(window!=null){boolean tablet=context.getResources().getConfiguration().screenWidthDp>=600;window.setGravity(tablet?Gravity.END|Gravity.CENTER_VERTICAL:Gravity.BOTTOM);window.setLayout(tablet?dp(420):WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT);if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(true);}}
    TextView text(String value,int size){TextView view=new TextView(context);view.setText(value);StudioTypography.text(view,size);view.setTextColor(StudioTypography.INK);return view;}
    Button button(String label,boolean primary){Button button=new Button(context);StudioTypography.button(button);button.setText(label);button.setBackground(shape(primary?StudioTypography.INK:0xfff4f4f4,12));if(primary)button.setTextColor(Color.WHITE);return button;}
    void action(String label,boolean primary,Runnable action){footer.setVisibility(View.VISIBLE);Button button=button(label,primary);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);footer.addView(button,p);button.setOnClickListener(v->action.run());}
    LinearLayout row(String label,String detail,String icon,Runnable action){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(14),0,dp(14));row.setMinimumHeight(StudioTypography.controlHeight(context));row.setClickable(true);row.setFocusable(true);row.setBackgroundResource(android.R.drawable.list_selector_background);if(!icon.isEmpty()){ImageView image=new ImageView(context);image.setImageDrawable(new StudioIcon(icon));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(22),dp(22));p.rightMargin=dp(12);row.addView(image,p);}LinearLayout copy=new LinearLayout(context);copy.setOrientation(LinearLayout.VERTICAL);TextView name=text(label,15);copy.addView(name);if(!detail.isEmpty()){TextView secondary=text(detail,13);StudioTypography.meta(secondary);secondary.setPadding(0,dp(4),0,0);copy.addView(secondary);}row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));body.addView(row,new LinearLayout.LayoutParams(-1,-2));row.setOnClickListener(v->action.run());return row;
    }
    private GradientDrawable shape(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private int dp(int value){return StudioTypography.dp(context,value);}
}
