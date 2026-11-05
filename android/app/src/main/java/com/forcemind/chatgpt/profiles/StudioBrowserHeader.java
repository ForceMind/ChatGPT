package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** The same compact, title-first chrome for retained WebView and Gecko pages. */
final class StudioBrowserHeader extends LinearLayout {
    interface Host {
        void accounts();
        void select(String tabId);
        void tabs();
        void refresh();
        void stop();
        void more();
    }
    private final Host host;
    private final LinearLayout account,strip;
    private final TextView name;
    private final View dot;
    private final ImageButton refresh;
    private final HorizontalScrollView scroll;
    private final List<TextView> titles=new ArrayList<>();
    private String fingerprint="";
    private int activeIndex,previousWidth;
    private boolean loading,scrollToSelection;

    StudioBrowserHeader(Context context,Host host){
        super(context);this.host=host;setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(6),dp(3),dp(2),dp(3));setBackgroundColor(Color.WHITE);
        account=new LinearLayout(context);account.setGravity(Gravity.CENTER_VERTICAL);account.setMinimumHeight(StudioTypography.controlHeight(context));account.setPadding(dp(4),0,dp(4),0);account.setClickable(true);account.setFocusable(true);account.setBackgroundResource(android.R.drawable.list_selector_background);account.setOnClickListener(v->host.accounts());
        dot=new View(context);LayoutParams point=new LayoutParams(dp(6),dp(6));point.rightMargin=dp(5);account.addView(dot,point);
        name=new TextView(context);StudioTypography.text(name,14);name.setTextColor(StudioTypography.INK);name.setMaxLines(1);name.setEllipsize(TextUtils.TruncateAt.END);account.addView(name,new LayoutParams(0,-2,1));
        ImageView arrow=new ImageView(context);arrow.setImageDrawable(new StudioIcon("chevron"));LayoutParams ap=new LayoutParams(dp(10),dp(10));ap.leftMargin=dp(2);account.addView(arrow,ap);addView(account,new LayoutParams(dp(76),-2));
        scroll=new HorizontalScrollView(context);scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(true);strip=new LinearLayout(context);strip.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(strip,new HorizontalScrollView.LayoutParams(-2,-1));addView(scroll,new LayoutParams(0,-2,1));
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=previousWidth){previousWidth=r-l;sizeTabs(r-l);}revealSelection();});
        refresh=icon("refresh","刷新页面");refresh.setOnClickListener(v->{if(this.loading)host.stop();else host.refresh();});addView(refresh,new LayoutParams(dp(48),StudioTypography.controlHeight(context)));
        ImageButton more=icon("more","更多功能");more.setOnClickListener(v->host.more());addView(more,new LayoutParams(dp(48),StudioTypography.controlHeight(context)));
    }
    void render(WorkspaceState.Account owner,boolean loading,boolean pageActionsEnabled){
        this.loading=loading;refresh.setImageDrawable(new StudioIcon(loading?"close":"refresh"));refresh.setContentDescription(loading?"停止加载":"刷新页面");refresh.setEnabled(pageActionsEnabled);refresh.setAlpha(pageActionsEnabled?1f:.42f);
        StringBuilder key=new StringBuilder(owner.id).append('|').append(owner.name).append('|').append(owner.color).append('|').append(owner.activeTab);
        for(WorkspaceState.Tab tab:owner.tabs)key.append('|').append(tab.id).append(':').append(tab.title);
        if(key.toString().equals(fingerprint))return;fingerprint=key.toString();
        name.setText(owner.name);account.setContentDescription("切换账号，当前："+owner.name);GradientDrawable point=new GradientDrawable();point.setShape(GradientDrawable.OVAL);point.setColor(Color.parseColor(owner.color));dot.setBackground(point);
        titles.clear();strip.removeAllViews();activeIndex=0;
        for(WorkspaceState.Tab tab:owner.tabs){
            TextView title=new TextView(getContext());String text=tab.title==null||tab.title.trim().isEmpty()?"新对话":tab.title;boolean selected=tab.id.equals(owner.activeTab);
            title.setText(text);StudioTypography.text(title,15);title.setTextColor(StudioTypography.INK);title.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);title.setGravity(Gravity.CENTER_VERTICAL);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(dp(10),dp(4),dp(10),dp(4));title.setMinimumHeight(StudioTypography.controlHeight(getContext()));title.setSelected(selected);title.setFocusable(true);title.setClickable(true);title.setContentDescription((selected?"当前标签页：":"切换标签页：")+text);title.setBackground(shape(selected?0xffeeeeee:Color.WHITE));title.setOnClickListener(v->host.select(tab.id));title.setOnLongClickListener(v->{host.tabs();return true;});
            if(selected)activeIndex=titles.size();titles.add(title);strip.addView(title,new LayoutParams(dp(104),-2));
        }
        scrollToSelection=true;sizeTabs(scroll.getWidth());requestLayout();
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);revealSelection();}
    private void sizeTabs(int width){
        if(width<=0||titles.isEmpty())return;
        // Fit complete tabs; narrow windows keep one complete title, never shrink SP.
        int count=Math.min(titles.size(),width>=dp(168)?Math.max(2,width/dp(150)):1);
        int each=Math.max(1,(width-dp(4)*(count-1))/count);
        for(int i=0;i<titles.size();i++){TextView view=titles.get(i);LayoutParams p=(LayoutParams)view.getLayoutParams();p.width=each;p.height=Math.max(StudioTypography.controlHeight(getContext()),view.getMinimumHeight());p.rightMargin=i+1<titles.size()?dp(4):0;view.setLayoutParams(p);}
    }
    private void revealSelection(){
        if(!scrollToSelection||activeIndex>=titles.size())return;TextView selected=titles.get(activeIndex);if(selected.getWidth()<=0)return;
        int left=selected.getLeft(),right=selected.getRight(),current=scroll.getScrollX(),width=scroll.getWidth();
        if(left<current)scroll.scrollTo(left,0);else if(right>current+width)scroll.scrollTo(Math.max(0,right-width),0);scrollToSelection=false;
    }
    private ImageButton icon(String icon,String description){ImageButton view=new ImageButton(getContext());view.setImageDrawable(new StudioIcon(icon));view.setBackgroundResource(android.R.drawable.list_selector_background);view.setScaleType(ImageView.ScaleType.FIT_CENTER);view.setPadding(dp(13),dp(13),dp(13),dp(13));view.setContentDescription(description);return view;}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(9));return d;}
    private int dp(int value){return StudioTypography.dp(getContext(),value);}
}
