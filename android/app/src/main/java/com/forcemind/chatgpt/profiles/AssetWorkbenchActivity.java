package com.forcemind.chatgpt.profiles;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native account-owned controls; no JavaScript/native bridge is installed. */
public final class AssetWorkbenchActivity extends ComponentActivity {
    static final String ACCOUNT = "account";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private LinearLayout root,toolbar;
    private ListView list;
    private EditText search;
    private TextView subtitle;
    private AssetLedger ledger;
    private String accountId,accountName,mode="assets",filter="all",exportId;
    private volatile List<JSONObject> records=new ArrayList<>(),skills=new ArrayList<>(),shown=new ArrayList<>();
    private boolean busy;
    private int pendingTasks;
    private volatile boolean dead;
    private static final int INK=Color.rgb(23,23,23),GRAY=Color.rgb(245,245,245);

    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(state!=null)exportId=state.getString("exportId");accountId=getIntent().getStringExtra(ACCOUNT);mode=getIntent().getBooleanExtra("skills",false)?"skills":"assets";
        try{WorkspaceState.profile(accountId);WorkspaceState.Account owner=account();accountName=owner.name+(owner.email.isEmpty()?"":" · "+owner.email);}catch(Exception e){finish();return;}
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);root.setFocusableInTouchMode(true);root.requestFocus();
        root.setPadding(dp(16),0,dp(16),0);setContentView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            int bottom=Math.max(bars.bottom,insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom);
            v.setPadding(dp(16)+bars.left,bars.top,dp(16)+bars.right,bottom);return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header,new LinearLayout.LayoutParams(-1,dp(58)));
        Button back=button("返回网页");header.addView(back);back.setOnClickListener(v->finish());
        TextView title=text(mode.equals("assets")?"资产":"能力库",22);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));title.setPadding(dp(12),0,0,0);
        subtitle=text(accountName+" · 独立账号空间",12);root.addView(subtitle);
        search=new EditText(this);search.setSingleLine(true);search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});search.setTextSize(14);search.setHint(mode.equals("assets")?"搜索文件名":"搜索 Skill 名称、分类或任务");root.addView(search,new LinearLayout.LayoutParams(-1,dp(54)));
        toolbar=new LinearLayout(this);root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(46)));
        if(mode.equals("assets")){
            Button add=button("导入文件");toolbar.addView(add);add.setOnClickListener(v->chooseImport());
            Button filters=button("全部文件 ▾");toolbar.addView(filters);filters.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("查看文件")
                .setItems(new String[]{"全部文件","图片","网页 / HTML","文档 / 数据","收藏","回收站"},(d,i)->{filter=new String[]{"all","image","web","document","favorite","trash"}[i];filters.setText(new String[]{"全部文件 ▾","图片 ▾","网页 / HTML ▾","文档 / 数据 ▾","收藏 ▾","回收站 ▾"}[i]);render();}).show());
        }else{
            TextView count=text("120 个项目 Skill · 暂无预置智能体",12);toolbar.addView(count);
        }
        list=new ListView(this);list.setDividerHeight(0);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(Editable e){}});
        list.setOnItemClickListener((parent,view,position,id)->{JSONObject a=shown.get(position);if(mode.equals("skills"))showSkill(a);else assetActions(a);});
        load();
    }
    private WorkspaceState.Account account() throws Exception {
        return WorkspaceFiles.read(getFilesDir()).find(accountId);
    }
    private boolean available(){if(dead)return false;try{account();return true;}catch(Exception e){return false;}}
    private void load(){
        task(()->{
            account();
            if(mode.equals("assets")){ledger=new AssetLedger(getFilesDir(),accountId);records=ledger.list();}
            else{SkillCatalog catalog=new SkillCatalog(getAssets().open("capabilities/manifest.json"),getAssets().open("capabilities/skills.json"));skills=catalog.list();}
        },()->{render();if(ledger!=null&&ledger.recoveryNotice!=null)notice(ledger.recoveryNotice);});
    }
    private void render(){
        if(list==null)return;String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown=new ArrayList<>();
        for(JSONObject a:mode.equals("assets")?records:skills){
            if(mode.equals("assets")){
                boolean trash=a.optBoolean("trashed");if(trash!=filter.equals("trash"))continue;
                String mime=a.optString("mime"),name=a.optString("name").toLowerCase(Locale.ROOT);
                if(filter.equals("image")&&!mime.startsWith("image/"))continue;
                if(filter.equals("web")&&!mime.equals("text/html")&&!name.endsWith(".html"))continue;
                if(filter.equals("document")&&(mime.startsWith("image/")||mime.equals("text/html")))continue;
                if(filter.equals("favorite")&&!a.optBoolean("favorite"))continue;
            }
            String haystack=a.optString("name")+" "+a.optString("category")+" "+a.optString("description");
            if(!haystack.toLowerCase(Locale.ROOT).contains(query))continue;shown.add(a);
        }
        subtitle.setText(accountName+" · "+(mode.equals("assets")?shown.size()+" 个文件":shown.size()+" 个 Skill"));
        list.setAdapter(new ArrayAdapter<JSONObject>(this,android.R.layout.simple_list_item_2,android.R.id.text1,shown){
            @Override public View getView(int position,View convert,ViewGroup parent){
                View row=super.getView(position,convert,parent);JSONObject a=getItem(position);
                TextView first=row.findViewById(android.R.id.text1),second=row.findViewById(android.R.id.text2);first.setTextColor(INK);first.setTextSize(15);first.setText(a.optString("name"));
                second.setTextColor(Color.DKGRAY);second.setTextSize(12);
                second.setText(mode.equals("skills")?a.optString("category")+" · "+a.optString("description"):
                    stateName(a.optString("status"))+" · "+android.text.format.Formatter.formatShortFileSize(AssetWorkbenchActivity.this,a.optLong("size"))+(a.optBoolean("favorite")?" · 已收藏":""));
                row.setMinimumHeight(dp(76));return row;
            }
        });
        TextView empty=text(mode.equals("assets")?(ledger!=null&&ledger.recoveryNotice!=null?ledger.recoveryNotice:"这里还没有文件\n从 ChatGPT 下载或导入本机文件后，会按账号保存在这里。"):"没有匹配的 Skill",14);
        empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(40),dp(20),dp(40));
        View old=root.findViewWithTag("empty-assets");if(old!=null)root.removeView(old);
        empty.setTag("empty-assets");root.addView(empty);list.setEmptyView(empty);
    }
    private void chooseImport(){
        if(busy)return;
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{startActivityForResult(pick,10);}catch(RuntimeException e){notice("无法打开系统文件选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==10){
            List<Uri> uris=new ArrayList<>();
            if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null)uris.add(data.getData());
            if(uris.size()>100){notice("一次最多导入 100 个文件");return;}
            enqueue(()->{
                account();if(ledger==null)ledger=new AssetLedger(getFilesDir(),accountId);
                for(Uri uri:uris){
                    if(!"content".equals(uri.getScheme()))throw new IOException("请使用系统文档选择器");
                    String name="导入文件";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
                    try(InputStream input=getContentResolver().openInputStream(uri)){ledger.importCopy(input,name,getContentResolver().getType(uri),"import",null,this::available);}
                }
                records=ledger.list();
            },()->{render();notice("文件已复制到当前账号，原文件未修改");});
        }else if(request==11&&exportId!=null&&data.getData()!=null){
            String id=exportId;exportId=null;Uri destination=data.getData();
            enqueue(()->{
                account();if(ledger==null)ledger=new AssetLedger(getFilesDir(),accountId);if(!"content".equals(destination.getScheme()))throw new IOException("保存位置无效");
                File f=ledger.verifiedFile(id);
                try(InputStream in=java.nio.file.Files.newInputStream(f.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
                    if(out==null)throw new IOException("无法写入保存位置");byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }catch(Exception e){try{android.provider.DocumentsContract.deleteDocument(getContentResolver(),destination);}catch(Exception ignored){}throw e;}
            },()->notice("文件已导出"));
        }
    }
    private void assetActions(JSONObject a){
        String id=a.optString("id");
        if(a.optBoolean("trashed")){
            new AlertDialog.Builder(this).setTitle(a.optString("name")).setItems(new String[]{"恢复文件","彻底删除"},(d,i)->{
                if(i==0)mutate(()->ledger.trash(id,false));else new AlertDialog.Builder(this).setTitle("彻底删除此文件？").setMessage("仅删除应用中的副本，此操作无法恢复。").setNegativeButton("取消",null).setPositiveButton("删除",(x,w)->mutate(()->ledger.remove(id))).show();
            }).show();return;
        }
        new AlertDialog.Builder(this).setTitle(a.optString("name")).setItems(new String[]{"预览查看","导出副本",a.optBoolean("favorite")?"取消收藏":"收藏","重命名","移至回收站"},(d,i)->{
            if(i==0)startActivity(new Intent(this,AssetPreviewActivity.class).putExtra(ACCOUNT,accountId).putExtra("asset",id));
            if(i==1){exportId=id;try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(a.optString("mime","application/octet-stream")).putExtra(Intent.EXTRA_TITLE,a.optString("name")),11);}catch(RuntimeException e){exportId=null;notice("无法打开保存位置选择器");}}
            if(i==2)mutate(()->ledger.favorite(id,!a.optBoolean("favorite")));
            if(i==3){EditText name=new EditText(this);name.setText(a.optString("name"));new AlertDialog.Builder(this).setTitle("重命名应用中的副本").setView(name).setNegativeButton("取消",null).setPositiveButton("保存",(x,w)->mutate(()->ledger.rename(id,name.getText().toString()))).show();}
            if(i==4)mutate(()->ledger.trash(id,true));
        }).show();
    }
    private void showSkill(JSONObject skill){
        try{String prompt=SkillCatalog.prompt(skill);
            new AlertDialog.Builder(this).setTitle(skill.getString("name")).setMessage(prompt+"\n\n当前可复制指令，在网页手动添加文件并发送。")
                .setNegativeButton("关闭",null).setPositiveButton("复制指令",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Skill 任务",prompt));notice("指令已复制");}).show();
        }catch(Exception e){notice("无法读取 Skill");}
    }
    private interface Work {void run() throws Exception;}
    private void mutate(Work work){task(()->{account();work.run();records=ledger.list();},this::render);}
    private void task(Work work,Runnable success){
        if(busy){notice("请等待当前操作完成");return;}enqueue(work,success);
    }
    private void enqueue(Work work,Runnable success){
        if(executor.isShutdown())return;pendingTasks++;busy=true;subtitle.setText(accountName+" · 正在处理…");
        executor.execute(()->{String error=null;try{work.run();}catch(Exception e){error=e.getMessage()==null?"操作未完成":e.getMessage();}String message=error;
            runOnUiThread(()->{pendingTasks--;busy=pendingTasks>0;if(isFinishing()||isDestroyed())return;
                if(message!=null){notice(message);subtitle.setText(accountName+" · 操作未完成");}else success.run();
                if(busy)subtitle.setText(accountName+" · 正在处理…");
            });
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){if(exportId!=null)state.putString("exportId",exportId);super.onSaveInstanceState(state);}
    private Button button(String label){Button v=new Button(this);v.setText(label);v.setTextSize(12);v.setTextColor(INK);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GRAY));return v;}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(INK);return v;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String stateName(String value){switch(value){case "ready":return "已归档";case "processing":return "处理中";case "interrupted":return "任务已中断";case "deleting":return "删除未完成";default:return "任务未完成";}}
    private void notice(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){dead=true;executor.shutdownNow();super.onDestroy();}
}
