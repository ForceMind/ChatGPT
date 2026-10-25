package com.forcemind.chatgpt.profiles;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Recycled native card grid. Account identity remains visible on every card. */
final class TabOverview extends LinearLayout {
    interface Host {
        WorkspaceState state();boolean loaded(String id);android.graphics.Bitmap thumbnail(String id);
        void activate(String account,String tab);void add(String account);void close(String account,String tab);
        void restore(String account,String tab);void pin(String account,String tab);void rename(String account,String tab,String title);
        void move(String account,String tab,int direction);void done();
    }
    private final Host host;private final TextView summary,count;private final EditText search;private final LinearLayout filters;
    private final GridView grid;private final Cards adapter=new Cards();private String filter="all";
    private final List<Entry> visible=new ArrayList<>();
    private record Entry(WorkspaceState.Account account,WorkspaceState.Tab tab){}
    TabOverview(Context context,Host host){
        super(context);this.host=host;setOrientation(VERTICAL);setBackgroundColor(Color.rgb(247,247,247));setContentDescription("标签页总览");setClickable(true);
        LinearLayout top=new LinearLayout(context);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(18),dp(10),dp(12),0);top.setMinimumHeight(dp(78));addView(top,new LayoutParams(-1,-2));
        LinearLayout titles=new LinearLayout(context);titles.setOrientation(VERTICAL);TextView title=text("标签页",25);title.setTypeface(null,android.graphics.Typeface.BOLD);titles.addView(title);summary=text("",11);StudioTypography.meta(summary);titles.addView(summary);top.addView(titles,new LayoutParams(0,-2,1));Button done=button("完成");done.setOnClickListener(v->host.done());done.setMinWidth(dp(64));top.addView(done,new LayoutParams(-2,StudioTypography.controlHeight(context)));
        LinearLayout query=new LinearLayout(context);query.setGravity(Gravity.CENTER_VERTICAL);query.setPadding(dp(12),0,dp(8),0);query.setBackground(round(0xffeaeaea,0,12));LayoutParams qp=new LayoutParams(-1,StudioTypography.lineBox(context,15,1,16,46));qp.setMargins(dp(16),dp(8),dp(16),dp(8));addView(query,qp);ImageView magnifier=new ImageView(context);magnifier.setImageDrawable(new StudioIcon("search"));query.addView(magnifier,new LayoutParams(dp(18),dp(18)));
        search=new EditText(context);search.setSingleLine(true);search.setTextSize(15);search.setTextColor(StudioTypography.INK);search.setHintTextColor(StudioTypography.MUTED);search.setBackgroundColor(Color.TRANSPARENT);search.setHint("搜索标签或账号");search.setContentDescription("搜索标签或账号");search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});query.addView(search,new LayoutParams(0,-1,1));search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){refreshCards();}public void afterTextChanged(Editable e){}});
        HorizontalScrollView row=new HorizontalScrollView(context);row.setHorizontalScrollBarEnabled(false);filters=new LinearLayout(context);filters.setGravity(Gravity.CENTER_VERTICAL);filters.setPadding(dp(16),0,dp(8),0);row.addView(filters);row.setMinimumHeight(dp(56));addView(row,new LayoutParams(-1,-2));
        grid=new GridView(context);int screenWidth=getResources().getConfiguration().screenWidthDp;float scale=Math.max(1f,getResources().getConfiguration().fontScale);int minimumCard=Math.round(144*scale);grid.setNumColumns(Math.min(screenWidth>=600?3:2,Math.max(1,(screenWidth-20)/(minimumCard+12))));grid.setHorizontalSpacing(dp(12));grid.setVerticalSpacing(dp(12));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setPadding(dp(16),dp(4),dp(16),dp(16));grid.setClipToPadding(false);grid.setVerticalScrollBarEnabled(false);grid.setAdapter(adapter);addView(grid,new LayoutParams(-1,0,1));
        LinearLayout bottom=new LinearLayout(context);bottom.setGravity(Gravity.CENTER_VERTICAL);bottom.setPadding(dp(12),dp(4),dp(16),dp(8));bottom.setBackgroundColor(0xfff7f7f7);bottom.setMinimumHeight(dp(68));addView(bottom,new LayoutParams(-1,-2));ImageButton recent=icon("history","最近关闭的标签页");recent.setOnClickListener(v->recent());bottom.addView(recent,new LayoutParams(dp(48),dp(48)));Space left=new Space(context);bottom.addView(left,new LayoutParams(0,1,1));Button add=button(getResources().getConfiguration().fontScale>1.25f?"＋ 新建":"＋ 新建标签页");add.setContentDescription("新建标签页");add.setTextColor(Color.WHITE);add.setBackground(round(0xff171717,0,25));add.setPadding(dp(20),0,dp(20),0);add.setOnClickListener(v->host.add(filter.equals("all")?host.state().activeAccount:filter));bottom.addView(add,new LayoutParams(-2,StudioTypography.controlHeight(context)));Space right=new Space(context);bottom.addView(right,new LayoutParams(0,1,1));count=text("",13);StudioTypography.meta(count);count.setGravity(Gravity.END);count.setMinWidth(dp(36));bottom.addView(count,new LayoutParams(-2,-2));refresh();
    }
    String query(){return search.getText().toString();}String filter(){return filter;}int position(){return grid.getFirstVisiblePosition();}
    void restoreUI(String filter,String query,int position){if(filter.equals("all")||host.state().accounts.stream().anyMatch(a->a.id.equals(filter)))this.filter=filter;search.setText(query);refresh();grid.setSelection(Math.max(0,position));}
    void refresh(){
        WorkspaceState state=host.state();int total=state.accounts.stream().mapToInt(a->a.tabs.size()).sum();summary.setText(total+" 个标签 · "+state.accounts.size()+" 个账号空间");filters.removeAllViews();chip("all","全部 "+total,null);
        for(WorkspaceState.Account a:state.accounts)chip(a.id,a.name+" "+a.tabs.size(),a.color);refreshCards();
    }
    private void chip(String id,String label,String color){
        LinearLayout item=new LinearLayout(getContext());item.setGravity(Gravity.CENTER);item.setPadding(dp(13),0,dp(13),0);item.setBackground(round(id.equals(filter)?0xff171717:Color.WHITE,id.equals(filter)?0xff171717:0xffdddddd,23));item.setContentDescription("按账号筛选："+label);item.setFocusable(true);item.setClickable(true);
        if(color!=null){View dot=new View(getContext());dot.setBackground(round(Color.parseColor(color),0,4));LayoutParams d=new LayoutParams(dp(6),dp(6));d.setMargins(0,0,dp(7),0);item.addView(dot,d);}TextView name=text(label,12);name.setTextColor(id.equals(filter)?Color.WHITE:Color.BLACK);item.addView(name);LayoutParams p=new LayoutParams(-2,StudioTypography.controlHeight(getContext()));p.setMargins(0,0,dp(8),0);filters.addView(item,p);item.setOnClickListener(v->{filter=id;refresh();});
    }
    private void refreshCards(){
        if(grid==null)return;String q=query().trim().toLowerCase(Locale.ROOT);visible.clear();for(WorkspaceState.Account a:host.state().accounts)for(WorkspaceState.Tab t:a.tabs)if((filter.equals("all")||filter.equals(a.id))&&(t.title+" "+a.name).toLowerCase(Locale.ROOT).contains(q))visible.add(new Entry(a,t));
        visible.sort((a,b)->Boolean.compare(b.tab.pinned,a.tab.pinned));count.setText(visible.size()+" 个");adapter.notifyDataSetChanged();
    }
    private void menu(Entry entry){String account=entry.account.id,id=entry.tab.id;String[] options={entry.tab.pinned?"取消固定":"固定标签页","重命名","向前移动","向后移动","关闭标签页"};new AlertDialog.Builder(getContext()).setTitle(entry.tab.title).setItems(options,(d,n)->{switch(n){case 0->host.pin(account,id);case 1->rename(entry);case 2->host.move(account,id,-1);case 3->host.move(account,id,1);case 4->host.close(account,id);}refresh();}).show();}
    private void rename(Entry entry){EditText value=new EditText(getContext());value.setText(entry.tab.title);value.setSingleLine(true);value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});value.setContentDescription("标签名称");new AlertDialog.Builder(getContext()).setTitle("重命名标签").setView(value).setNegativeButton("取消",null).setPositiveButton("保存",(d,n)->{String title=value.getText().toString().trim();if(!title.isEmpty())host.rename(entry.account.id,entry.tab.id,title);refresh();}).show();}
    private void recent(){List<Entry> closed=new ArrayList<>();for(WorkspaceState.Account a:host.state().accounts)if(filter.equals("all")||filter.equals(a.id))for(int i=a.closedTabs.size()-1;i>=0;i--)closed.add(new Entry(a,a.closedTabs.get(i)));if(closed.isEmpty()){new AlertDialog.Builder(getContext()).setTitle("最近关闭").setMessage("还没有关闭的标签页。").setPositiveButton("关闭",null).show();return;}String[] labels=closed.stream().map(e->e.tab.title+"\n"+e.account.name).toArray(String[]::new);new AlertDialog.Builder(getContext()).setTitle("最近关闭").setItems(labels,(d,n)->{Entry e=closed.get(n);host.restore(e.account.id,e.tab.id);}).setNegativeButton("关闭",null).show();}
    private final class Cards extends BaseAdapter {
        public int getCount(){return visible.size();}public Object getItem(int n){return visible.get(n);}public long getItemId(int n){return n;}
        public View getView(int n,View recycled,ViewGroup parent){
            Entry entry=visible.get(n);Card card=recycled instanceof LinearLayout?(Card)recycled.getTag():new Card();LinearLayout root=card.root;WorkspaceState state=host.state();boolean active=entry.account.id.equals(state.activeAccount)&&entry.tab.id.equals(entry.account.activeTab);root.setBackground(round(Color.WHITE,active?0xff171717:0xffdddddd,16));
            card.owner.setText(entry.account.name);card.dot.setBackground(round(Color.parseColor(entry.account.color),0,4));card.title.setText((entry.tab.pinned?"● ":"")+entry.tab.title);card.previewTitle.setText(entry.tab.title);card.status.setText(host.loaded(entry.tab.id)?"网页已保留":"点按载入");Bitmap bitmap=host.thumbnail(entry.tab.id);card.image.setImageBitmap(bitmap);card.image.setVisibility(bitmap==null?GONE:VISIBLE);card.placeholder.setVisibility(bitmap==null?VISIBLE:GONE);
            root.setContentDescription("标签页："+entry.tab.title+"，账号："+entry.account.name);root.setOnClickListener(v->host.activate(entry.account.id,entry.tab.id));root.setOnLongClickListener(v->{menu(entry);return true;});card.close.setContentDescription("关闭标签页："+entry.tab.title);card.close.setOnClickListener(v->{host.close(entry.account.id,entry.tab.id);refresh();});card.more.setContentDescription("管理标签页："+entry.tab.title);card.more.setOnClickListener(v->menu(entry));return root;
        }
    }
    private final class Card {
        final LinearLayout root=new LinearLayout(getContext()),placeholder=new LinearLayout(getContext());final TextView owner=text("",10),title=text("",15),previewTitle=text("",14),status=text("",11);final View dot=new View(getContext());final ImageButton close=icon("close","关闭标签页"),more=icon("more","管理标签页");final ImageView image=new ImageView(getContext());
        Card(){int headerHeight=StudioTypography.lineBox(getContext(),13,1,12,48),footerHeight=StudioTypography.lineBox(getContext(),15,2,12,54);StudioTypography.meta(owner);root.setTag(this);root.setOrientation(VERTICAL);root.setClipToOutline(true);root.setLayoutParams(new AbsListView.LayoutParams(-1,Math.max(dp(232),headerHeight+footerHeight+dp(100))));root.setFocusable(true);root.setClickable(true);
            LinearLayout header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(10),0,0,0);root.addView(header,new LayoutParams(-1,headerHeight));LayoutParams dotp=new LayoutParams(dp(6),dp(6));dotp.setMargins(0,0,dp(6),0);header.addView(dot,dotp);owner.setSingleLine(true);owner.setEllipsize(TextUtils.TruncateAt.END);header.addView(owner,new LayoutParams(0,-2,1));header.addView(close,new LayoutParams(dp(48),dp(48)));
            FrameLayout preview=new FrameLayout(getContext());preview.setBackgroundColor(0xfff8f8f8);root.addView(preview,new LayoutParams(-1,0,1));image.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.addView(image,new FrameLayout.LayoutParams(-1,-1));placeholder.setOrientation(VERTICAL);placeholder.setGravity(Gravity.CENTER_VERTICAL);placeholder.setPadding(dp(12),dp(8),dp(12),dp(8));previewTitle.setMaxLines(3);placeholder.addView(previewTitle);status.setTextColor(Color.DKGRAY);LayoutParams sp=new LayoutParams(-1,-2);sp.topMargin=dp(14);placeholder.addView(status,sp);preview.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
            LinearLayout footer=new LinearLayout(getContext());footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(10),0,0,0);root.addView(footer,new LayoutParams(-1,footerHeight));title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);footer.addView(title,new LayoutParams(0,-2,1));footer.addView(more,new LayoutParams(dp(48),dp(48)));
        }
    }
    private ImageButton icon(String name,String label){ImageButton b=new ImageButton(getContext());b.setImageDrawable(new StudioIcon(name));b.setScaleType(ImageView.ScaleType.FIT_CENTER);b.setPadding(dp(13),dp(13),dp(13),dp(13));b.setBackgroundColor(Color.TRANSPARENT);b.setContentDescription(label);return b;}
    private Button button(String label){Button b=new Button(getContext());StudioTypography.button(b);b.setText(label);b.setAllCaps(false);b.setBackgroundColor(Color.TRANSPARENT);return b;}
    private TextView text(String value,int size){TextView t=new TextView(getContext());t.setText(value);StudioTypography.text(t,Math.max(14,size));t.setTextColor(0xff171717);return t;}
    private GradientDrawable round(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(stroke==0xff171717?2:1),stroke);return d;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
