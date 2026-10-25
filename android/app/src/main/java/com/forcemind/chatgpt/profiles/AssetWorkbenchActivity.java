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
    private HandoffDialog handoffDialog;
    private volatile JSONObject pendingRender;
    private boolean renderLaunched;
    private int pendingTasks;
    private volatile boolean dead;
    private static final int INK=Color.rgb(23,23,23),GRAY=Color.rgb(245,245,245);

    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(state!=null)exportId=state.getString("exportId");accountId=getIntent().getStringExtra(ACCOUNT);mode=getIntent().getBooleanExtra("skills",false)?"skills":"assets";
        if(state!=null&&state.containsKey("pendingRender"))try{
            pendingRender=new JSONObject(state.getString("pendingRender"));renderLaunched=state.getBoolean("renderLaunched");
            if(!renderLaunched){try{RenderTasks.cleanup(getCacheDir(),pendingRender.getString("task"));}catch(Exception ignored){}pendingRender=null;}
        }catch(JSONException ignored){}
        try{WorkspaceState.profile(accountId);WorkspaceState.Account owner=account();accountName=owner.name+(owner.email.isEmpty()?"":" · "+owner.email);}catch(Exception e){finish();return;}
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);root.setFocusableInTouchMode(true);root.requestFocus();
        root.setPadding(dp(16),0,dp(16),0);setContentView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            int bottom=Math.max(bars.bottom,insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom);
            v.setPadding(dp(16)+bars.left,bars.top,dp(16)+bars.right,bottom);return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setMinimumHeight(dp(58));root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        Button back=button("返回网页");header.addView(back);back.setOnClickListener(v->finish());
        TextView title=text(mode.equals("assets")?"资产":"能力库",22);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));title.setPadding(dp(12),0,0,0);
        subtitle=text(accountName+" · 独立账号空间",12);root.addView(subtitle);
        search=new EditText(this);search.setSingleLine(true);search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});search.setTextSize(15);search.setHint(mode.equals("assets")?"搜索文件名":"搜索 Skill 名称、分类或任务");search.setHintTextColor(StudioTypography.MUTED);root.addView(search,new LinearLayout.LayoutParams(-1,StudioTypography.lineBox(this,15,1,16,54)));
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setMinimumHeight(StudioTypography.controlHeight(this));HorizontalScrollView toolsScroll=new HorizontalScrollView(this);toolsScroll.setHorizontalScrollBarEnabled(false);toolsScroll.setFillViewport(true);toolsScroll.addView(toolbar,new android.widget.FrameLayout.LayoutParams(-2,-2));root.addView(toolsScroll,new LinearLayout.LayoutParams(-1,-2));
        if(mode.equals("assets")){
            Button add=button("导入文件");toolbar.addView(add);add.setOnClickListener(v->chooseImport());
            Button send=button("交给 GPT");toolbar.addView(send);send.setOnClickListener(v->openHandoff(Collections.emptyList(),""));
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
                LinearLayout row;
                if(convert instanceof LinearLayout&&"studio-asset-row".equals(convert.getTag()))row=(LinearLayout)convert;
                else{row=new LinearLayout(AssetWorkbenchActivity.this);row.setTag("studio-asset-row");row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setLayoutParams(new AbsListView.LayoutParams(-1,-2));TextView name=new TextView(AssetWorkbenchActivity.this);name.setId(android.R.id.text1);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(name);TextView description=new TextView(AssetWorkbenchActivity.this);description.setId(android.R.id.text2);description.setMaxLines(mode.equals("skills")?3:2);description.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(description);}
                JSONObject a=getItem(position);
                TextView first=row.findViewById(android.R.id.text1),second=row.findViewById(android.R.id.text2);first.setTextColor(INK);first.setTextSize(15);first.setText(a.optString("name"));
                second.setTextColor(Color.DKGRAY);StudioTypography.text(second,14);
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
        super.onActivityResult(request,result,data);
        if(request==12){renderResult(result,data);return;}
        if(result!=RESULT_OK||data==null)return;
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
        new AlertDialog.Builder(this).setTitle(a.optString("name")).setItems(new String[]{"预览查看","导出副本","转换格式",a.optBoolean("favorite")?"取消收藏":"收藏","重命名","移至回收站","交给 GPT"},(d,i)->{
            if(i==0)startActivity(new Intent(this,AssetPreviewActivity.class).putExtra(ACCOUNT,accountId).putExtra("asset",id));
            if(i==1){exportId=id;try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(a.optString("mime","application/octet-stream")).putExtra(Intent.EXTRA_TITLE,a.optString("name")),11);}catch(RuntimeException e){exportId=null;notice("无法打开保存位置选择器");}}
            if(i==2)convert(a);
            if(i==3)mutate(()->ledger.favorite(id,!a.optBoolean("favorite")));
            if(i==4){EditText name=new EditText(this);name.setText(a.optString("name"));new AlertDialog.Builder(this).setTitle("重命名应用中的副本").setView(name).setNegativeButton("取消",null).setPositiveButton("保存",(x,w)->mutate(()->ledger.rename(id,name.getText().toString()))).show();}
            if(i==5)mutate(()->ledger.trash(id,true));
            if(i==6)openHandoff(Collections.singletonList(id),"");
        }).show();
    }
    private void convert(JSONObject asset){
        if(busy)return;
        String kind=ConversionType.of(asset);
        if(kind.equals("pdf")){convertPdf(asset);return;}
        if(kind.equals("markdown")){new AlertDialog.Builder(this).setTitle("选择转换格式").setItems(new String[]{"HTML","PDF"},(d,i)->{if(i==0)convertDocument(asset);else convertRender(asset);}).show();return;}
        if(!RenderTasks.kind(asset).isEmpty()){convertRender(asset);return;}
        if(!DocumentConversions.formats(asset).isEmpty()){convertDocument(asset);return;}
        if(!kind.equals("image")){notice("此文件暂不支持本机转换，可导出后处理");return;}
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),dp(12),dp(24),0);
        fields.addView(text("新建副本，保留原件。动图仅转换第一帧；JPEG 的透明区域使用白底。",13));
        Spinner format=new Spinner(this),size=new Spinner(this);
        format.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"PNG","JPEG","WebP"}));
        size.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"原尺寸（最多 1600 万像素）","长边 640","长边 1280","长边 2048","长边 4096"}));size.setSelection(2);
        fields.addView(text("目标格式",12));fields.addView(format);fields.addView(text("目标尺寸，不放大原图",12));fields.addView(size);
        TextView value=text("JPEG / WebP 质量：90",12);fields.addView(value);SeekBar quality=new SeekBar(this);quality.setMax(99);quality.setProgress(89);fields.addView(quality);
        quality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){value.setText("JPEG / WebP 质量："+(p+1));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        new AlertDialog.Builder(this).setTitle("转换图片副本").setView(fields).setNegativeButton("取消",null).setPositiveButton("开始转换",(d,w)->{
            String output=new String[]{"png","jpeg","webp"}[format.getSelectedItemPosition()];int maximum=new int[]{0,640,1280,2048,4096}[size.getSelectedItemPosition()];int q=quality.getProgress()+1;
            task(()->{account();ImageConversions.convert(this,ledger,asset.optString("id"),output,maximum,q,this::available);records=ledger.list();},()->{render();notice("转换副本已归档，原文件未修改");});
        }).show();
    }
    private Spinner choices(LinearLayout fields,String title,String[] values,int selection){
        fields.addView(text(title,12));Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values));spinner.setSelection(selection);fields.addView(spinner);return spinner;
    }
    private void convertRender(JSONObject asset){
        if(pendingRender!=null){notice("请等待当前页面转换完成");return;}
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),dp(12),dp(24),0);
        fields.addView(text("生成当前账号的新副本，保留原件。仅使用静态页面内容，脚本和外部资源不加载。\nPDF 按屏幕图像分页为 A4，文字不可选择，打印专用 CSS 不生效。图片最多 1600 万像素，输入上限 2 MiB。",13));
        String kind=RenderTasks.kind(asset);Spinner format=choices(fields,"目标格式",RenderTasks.formats(kind).toArray(new String[0]),0);
        Spinner width=choices(fields,"排版 / 图片宽度（像素）",new String[]{"640","1280","2048","4096"},1);
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);
        new AlertDialog.Builder(this).setTitle("页面转换副本").setView(scroll).setNegativeButton("取消",null).setPositiveButton("开始转换",(d,w)->{
            String output=String.valueOf(format.getSelectedItem());int pixels=new int[]{640,1280,2048,4096}[width.getSelectedItemPosition()];
            task(()->{account();pendingRender=RenderTasks.prepare(getCacheDir(),ledger,asset.optString("id"),output,pixels,90,accountName,this::available);if(!available()){RenderTasks.cleanup(getCacheDir(),pendingRender.getString("task"));pendingRender=null;throw new IOException("转换已取消");}},()->{
                try{renderLaunched=true;startActivityForResult(new Intent(this,AssetRenderActivity.class).putExtra("task",pendingRender.optString("task")),12);}
                catch(RuntimeException e){renderLaunched=false;renderResult(RESULT_CANCELED,new Intent().putExtra("error","无法打开本机转换窗口"));}
            });
        }).show();
    }
    private void renderResult(int result,Intent data){
        JSONObject pending=pendingRender;pendingRender=null;renderLaunched=false;
        if(pending==null){notice("转换已中断，原文件保留，可以重新转换");return;}
        enqueue(()->{
            try{
                account();if(ledger==null)ledger=new AssetLedger(getFilesDir(),accountId);
                if(result!=RESULT_OK||data==null||!pending.getString("task").equals(data.getStringExtra("task")))throw new IOException(data==null?"转换已取消，原件保留":data.getStringExtra("error")==null?"转换未完成，原件保留":data.getStringExtra("error"));
                RenderedConversions.accept(this,ledger,pending,this::available);
            }finally{try{RenderTasks.cleanup(getCacheDir(),pending.getString("task"));}finally{if(ledger!=null)records=ledger.list();}}
        },()->{render();notice("转换副本已归档，原文件未修改");});
    }
    private void convertPdf(JSONObject asset){
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),dp(12),dp(24),0);
        fields.addView(text("每个选中页生成一个副本，保留原件。支持 1,3-5，最多 20 个不同页码；已成功生成的页在中断后仍保留。",13));
        EditText pages=new EditText(this);pages.setText("1");pages.setSingleLine(true);pages.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});fields.addView(text("页码",12));fields.addView(pages);
        Spinner format=choices(fields,"目标格式",new String[]{"PNG","JPEG"},0),width=choices(fields,"图片宽度（像素）",new String[]{"640","1280","2048","4096"},1);
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);
        new AlertDialog.Builder(this).setTitle("PDF 页面转图片").setView(scroll).setNegativeButton("取消",null).setPositiveButton("开始转换",(d,w)->{
            String selected=pages.getText().toString(),output=String.valueOf(format.getSelectedItem());int pixels=new int[]{640,1280,2048,4096}[width.getSelectedItemPosition()];
            task(()->{account();try{RenderedConversions.pdfPages(this,ledger,asset.optString("id"),PageSelection.parse(selected),output,pixels,90,this::available);}finally{records=ledger.list();}},()->{render();notice("PDF 图片副本已归档，原文件未修改");});
        }).show();
    }
    private void convertDocument(JSONObject asset){
        String format=DocumentConversions.formats(asset).get(0);
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),dp(12),dp(24),0);
        String detail=format.equals("JSON")?"保留每一列的文本值。表头不能为空或重复，各行列数必须一致。":format.equals("CSV")?"需要字段一致的非空对象数组，不接受嵌套对象或数组。保留数值文本，公式前缀加单引号；null 输出为空单元格。":"转换标题、列表、代码块、表格和任务列表。原始 HTML 标签作为文本显示，脚本和外部资源不加载。";
        fields.addView(text("生成 "+format+" 新副本并归档，原文件不变。\n\n"+detail+"\n\n"+(format.equals("HTML")?"Markdown 上限 2 MiB。":"原文件上限 8 MiB、5 万行、20 万单元格。")+"转换结果上限 16 MiB。",13));
        Spinner delimiter=new Spinner(this);
        if(format.equals("JSON")){
            fields.addView(text("CSV 分隔符",12));delimiter.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"自动识别","逗号 ,","制表符 Tab","分号 ;","竖线 |"}));fields.addView(delimiter);
        }
        new AlertDialog.Builder(this).setTitle("转换为 "+format).setView(fields).setNegativeButton("取消",null).setPositiveButton("开始转换",(d,w)->{
            Character separator=delimiter.getSelectedItemPosition()<=0?null:new char[]{',','\t',';','|'}[delimiter.getSelectedItemPosition()-1];
            task(()->{account();DocumentConversions.convert(getCacheDir(),ledger,asset.optString("id"),format,separator,this::available);records=ledger.list();},()->{render();notice("转换副本已归档，原文件未修改");});
        }).show();
    }
    private void showSkill(JSONObject skill){
        try{String prompt=SkillCatalog.prompt(skill);
            new AlertDialog.Builder(this).setTitle(skill.getString("name")).setMessage(prompt)
                .setNeutralButton("交给 GPT",(d,w)->openHandoff(Collections.emptyList(),skill.optString("id")))
                .setNegativeButton("关闭",null).setPositiveButton("复制指令",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Skill 任务",prompt));notice("指令已复制");}).show();
        }catch(Exception e){notice("无法读取 Skill");}
    }
    private interface Work {void run() throws Exception;}
    private void openHandoff(List<String> initial,String skillId){
        final WorkspaceState[] snapshot=new WorkspaceState[1];final SkillCatalog[] catalog=new SkillCatalog[1];final List<JSONObject> files=new ArrayList<>();
        task(()->{snapshot[0]=WorkspaceFiles.read(getFilesDir());snapshot[0].find(accountId);catalog[0]=new SkillCatalog(getAssets().open("capabilities/manifest.json"),getAssets().open("capabilities/skills.json"));files.addAll(new AssetLedger(getFilesDir(),accountId).list());},()->{
            try{if(handoffDialog!=null)handoffDialog.close();handoffDialog=new HandoffDialog(this,snapshot[0],accountId,catalog[0],files,initial,skillId);}catch(Exception e){notice("无法准备任务，请检查账号和资产库");}
        });
    }
    private void mutate(Work work){task(()->{account();work.run();records=ledger.list();},this::render);}
    private void task(Work work,Runnable success){
        if(busy){notice("请等待当前操作完成");return;}enqueue(work,success);
    }
    private void enqueue(Work work,Runnable success){
        if(executor.isShutdown())return;pendingTasks++;busy=true;subtitle.setText(accountName+" · 正在处理…");
        executor.execute(()->{String error=null;try{work.run();}catch(Exception e){error=e.getMessage()==null?"操作未完成":e.getMessage();}String message=error;
            runOnUiThread(()->{pendingTasks--;busy=pendingTasks>0;if(isFinishing()||isDestroyed())return;
                if(message!=null){if(ledger!=null&&mode.equals("assets"))render();notice(message);subtitle.setText(accountName+" · "+message);}else success.run();
                if(busy)subtitle.setText(accountName+" · 正在处理…");
            });
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){if(exportId!=null)state.putString("exportId",exportId);if(pendingRender!=null){state.putString("pendingRender",pendingRender.toString());state.putBoolean("renderLaunched",renderLaunched);}super.onSaveInstanceState(state);}
    private Button button(String label){Button v=new Button(this);StudioTypography.button(v);v.setText(label);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GRAY));return v;}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);StudioTypography.text(v,Math.max(14,size));v.setTextColor(INK);return v;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String stateName(String value){switch(value){case "ready":return "已归档";case "processing":return "处理中";case "interrupted":return "任务已中断";case "deleting":return "删除未完成";default:return "任务未完成";}}
    private void notice(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){dead=true;if(handoffDialog!=null)handoffDialog.close();executor.shutdownNow();if(pendingRender!=null&&!renderLaunched&&!isChangingConfigurations())try{RenderTasks.cleanup(getCacheDir(),pendingRender.getString("task"));}catch(Exception ignored){}super.onDestroy();}
}
