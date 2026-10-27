package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import org.json.*;

/** Native, inert view: saved text is never executed or sent to a webpage. */
final class OfflineSnapshotView extends LinearLayout {
    private final JSONObject page,record;private final FrameLayout body;private final TextView title,detail,picture,reconnect;private Bitmap bitmap;private boolean showingPicture;private String connection="";
    OfflineSnapshotView(Context context,JSONObject snapshot,Runnable retry)throws Exception{
        super(context);record=new JSONObject(snapshot.toString());page=record.getJSONObject("page");setTag("studio-offline-snapshot");setOrientation(VERTICAL);setBackgroundColor(Color.WHITE);setContentDescription("本地页面快照");setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout header=new LinearLayout(context);header.setOrientation(VERTICAL);header.setPadding(dp(12),dp(8),dp(12),dp(8));header.setBackgroundColor(0xfffafafa);addView(header,new LayoutParams(-1,-2));
        LinearLayout label=new LinearLayout(context);label.setOrientation(VERTICAL);title=text("上次保存的对话",14);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);detail=text("只读 · "+when(page.optLong("capturedAt")),13);detail.setTextColor(0xff555555);label.addView(title);label.addView(detail);
        LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);picture=button("查看画面");reconnect=button("重新连接");
        if(page.has("image")){actions.addView(picture);picture.setOnClickListener(v->toggle());}actions.addView(reconnect);reconnect.setOnClickListener(v->{connecting();retry.run();});
        if(getResources().getConfiguration().fontScale>=1.3f){header.addView(label,new LayoutParams(-1,-2));header.addView(actions,new LayoutParams(-1,-2));}
        else{LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(label,new LayoutParams(0,-2,1));row.addView(actions,new LayoutParams(-2,-2));header.addView(row,new LayoutParams(-1,-2));}
        View line=new View(context);line.setBackgroundColor(0xffdddddd);addView(line,new LayoutParams(-1,dp(1)));
        body=new FrameLayout(context);addView(body,new LayoutParams(-1,0,1));showText();
    }
    void connecting(){connection="正在连接";refreshDetail();}
    void unavailable(String reason){connection=reason;refreshDetail();}
    private String when(long value){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(value));}
    private void refreshDetail(){long stamp=showingPicture?page.optLong("imageCapturedAt",page.optLong("capturedAt")):page.optLong("capturedAt");detail.setText("只读 · "+(connection.isEmpty()?"":connection+" · ")+when(stamp));}
    private void toggle(){if(showingPicture)showText();else showPicture();}
    private void showText(){
        showingPicture=false;picture.setText("查看画面");title.setText("上次保存的对话");refreshDetail();
        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(true);LinearLayout messages=new LinearLayout(getContext());messages.setOrientation(VERTICAL);messages.setPadding(dp(18),dp(14),dp(18),dp(24));scroll.addView(messages);JSONArray rows=page.optJSONArray("messages");JSONObject anchor=record.optJSONObject("anchor");String target=anchor==null?"":anchor.optString("value");TextView anchorView=null;
        if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject message=rows.optJSONObject(i);if(message==null)continue;boolean user="user".equals(message.optString("role"));TextView label=text(user?"你":"assistant".equals(message.optString("role"))?"ChatGPT":"消息",14);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);label.setPadding(0,dp(12),0,dp(5));messages.addView(label);if(!target.isEmpty()&&target.equals(message.optString("id")))anchorView=label;TextView value=text(message.optString("text"),15);value.setTextIsSelectable(true);value.setLineSpacing(dp(3),1f);value.setPadding(user?dp(12):0,dp(8),user?dp(12):0,dp(8));if(user)value.setBackground(round(0xfff1f1f1,0));LayoutParams item=new LayoutParams(-1,-2);if(user)item.leftMargin=dp(48);messages.addView(value,item);}
        if(page.optBoolean("truncated")){TextView note=text("仅保存了部分对话内容。",13);note.setTextColor(0xff555555);note.setPadding(0,dp(16),0,0);messages.addView(note);}body.removeAllViews();body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        TextView savedAnchor=anchorView;scroll.post(()->{if(savedAnchor!=null)scroll.scrollTo(0,Math.max(0,savedAnchor.getTop()-(int)Math.round(anchor.optDouble("offset"))));else scroll.scrollTo(0,(int)Math.round(Math.max(0,messages.getHeight()-scroll.getHeight())*record.optDouble("ratio",0)));});
    }
    private void showPicture(){
        try{
            if(bitmap==null){byte[] bytes=Base64.getDecoder().decode(page.getString("image"));BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>720||bounds.outHeight>1440)throw new IllegalArgumentException();BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new IllegalArgumentException();}
            ImageView image=new ImageView(getContext());image.setAdjustViewBounds(true);image.setImageBitmap(bitmap);image.setContentDescription("上次保存的页面画面，只读");ScrollView scroll=new ScrollView(getContext());scroll.addView(image,new FrameLayout.LayoutParams(-1,-2));body.removeAllViews();body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));showingPicture=true;picture.setText("查看文字");title.setText("上次保存的画面");refreshDetail();
        }catch(Exception|OutOfMemoryError error){picture.setVisibility(GONE);showText();Toast.makeText(getContext(),"画面暂不可查看，已显示对话文字。",Toast.LENGTH_SHORT).show();}
    }
    @Override protected void onDetachedFromWindow(){super.onDetachedFromWindow();body.removeAllViews();bitmap=null;}
    private TextView text(String value,int size){TextView v=new TextView(getContext());v.setText(value);v.setTextColor(0xff202020);StudioTypography.text(v,size);return v;}
    private TextView button(String value){TextView v=text(value,13);v.setGravity(Gravity.CENTER);v.setMinHeight(StudioTypography.controlHeight(getContext()));v.setPadding(dp(8),dp(6),dp(8),dp(6));v.setBackground(round(Color.WHITE,0xffcccccc));v.setClickable(true);v.setFocusable(true);return v;}
    private GradientDrawable round(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private int dp(int n){return StudioTypography.dp(getContext(),n);}
}
