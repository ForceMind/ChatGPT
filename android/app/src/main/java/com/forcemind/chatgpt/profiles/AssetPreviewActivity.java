package com.forcemind.chatgpt.profiles;

import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class AssetPreviewActivity extends ComponentActivity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private LinearLayout root,controls;
    private TextView status;
    private ImageView picture;
    private WebView web;
    private AssetLedger ledger;
    private String id;
    private int page, pages=1;
    private boolean rendering;
    private volatile boolean dead;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        setContentView(root);ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(dp(16)+bars.left,bars.top,dp(16)+bars.right,bars.bottom);return insets;});
        controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);root.addView(controls,new LinearLayout.LayoutParams(-1,dp(54)));
        Button back=button("返回资产");controls.addView(back);back.setOnClickListener(v->finish());
        status=new TextView(this);status.setTextColor(Color.DKGRAY);status.setPadding(0,dp(10),0,dp(10));status.setText("正在读取文件…");root.addView(status);
        id=getIntent().getStringExtra("asset");String account=getIntent().getStringExtra(AssetWorkbenchActivity.ACCOUNT);
        executor.execute(()->{
            try{
                WorkspaceFiles.read(getFilesDir()).find(account);
                ledger=new AssetLedger(getFilesDir(),account);JSONObject a=ledger.get(id);File file=ledger.verifiedFile(id);
                String name=a.getString("name"),mime=a.getString("mime"),lower=name.toLowerCase(Locale.ROOT);
                if(mime.equals("application/pdf")||lower.endsWith(".pdf")){showPdf(file,name);return;}
                if((mime.startsWith("image/")&&!mime.equals("image/svg+xml"))&&!lower.endsWith(".svg")){
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);
                    if(options.outWidth<=0||options.outHeight<=0||(long)options.outWidth*options.outHeight>128_000_000L)throw new IOException("图片尺寸不可预览，请导出后查看");
                    options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
                    Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);if(bitmap==null)throw new IOException("无法读取图片");
                    runOnUiThread(()->showBitmap(bitmap,name+" · "+options.outWidth+" × "+options.outHeight));return;
                }
                if(!textFormat(mime,lower))throw new IOException("此格式请导出后使用对应应用查看");
                if(file.length()>2*1024*1024)throw new IOException("文本预览上限 2 MiB，请导出后查看");
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
                if(mime.equals("text/html")||lower.endsWith(".html")||lower.endsWith(".htm")||lower.endsWith(".svg")||mime.equals("image/svg+xml")){
                    String html=(lower.endsWith(".svg")||mime.equals("image/svg+xml"))?"<!doctype html><html><head></head><body>"+text+"</body></html>":text;
                    runOnUiThread(()->showHtml(html,name));return;
                }
                runOnUiThread(()->{if(dead)return;status.setText(name);ScrollView scroll=new ScrollView(this);TextView content=new TextView(this);content.setText(text);content.setTextIsSelectable(true);content.setTextColor(Color.rgb(23,23,23));content.setTextSize(14);content.setTypeface(Typeface.MONOSPACE);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));});
            }catch(Exception e){error(e);}
        });
    }
    private void showBitmap(Bitmap bitmap,String name){
        if(dead)return;status.setText(name);
        if(picture==null){picture=new ImageView(this);picture.setAdjustViewBounds(true);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(picture,new LinearLayout.LayoutParams(-1,0,1));}
        picture.setImageBitmap(bitmap);picture.setContentDescription("文件图片预览");
    }
    private void showPdf(File file,String name) throws Exception {
        Bitmap bitmap;
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(descriptor)){
            pages=pdf.getPageCount();if(pages<1)throw new IOException("PDF 没有可预览的页面");page=Math.max(0,Math.min(page,pages-1));
            try(PdfRenderer.Page selected=pdf.openPage(page)){
                float scale=Math.min(2048f/selected.getWidth(),2048f/selected.getHeight());
                bitmap=Bitmap.createBitmap(Math.max(1,Math.round(selected.getWidth()*scale)),Math.max(1,Math.round(selected.getHeight()*scale)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                selected.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
        }
        Bitmap image=bitmap;runOnUiThread(()->{
            if(dead)return;showBitmap(image,name+" · "+(page+1)+" / "+pages);
            if(controls.getChildCount()==1){
                Button before=button("上一页"),after=button("下一页");controls.addView(before);controls.addView(after);
                before.setOnClickListener(v->renderPdf(-1,name));after.setOnClickListener(v->renderPdf(1,name));
            }
        });
    }
    private void renderPdf(int delta,String name){
        if(executor.isShutdown()||rendering)return;rendering=true;executor.execute(()->{try{int next=page+delta;if(next<0||next>=pages)return;page=next;showPdf(ledger.verifiedFile(id),name);}catch(Exception e){error(e);}finally{runOnUiThread(()->rendering=false);}});
    }
    @SuppressWarnings("SetJavaScriptEnabled")
    private void showHtml(String html,String name){
        if(dead)return;status.setText(name+" · HTML 静态预览，外部资源不可用");
        web=new WebView(this);web.setTag("asset-html-preview");
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(false);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setGeolocationEnabled(false);settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){root.removeView(view);view.destroy();web=null;status.setText("预览进程已退出。原文件已保留，可以返回重试或导出。");return true;}
        });
        String policy="<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">";
        java.util.regex.Matcher head=java.util.regex.Pattern.compile("(?i)<head\\b[^>]*>").matcher(html);
        String safe=head.find()?html.substring(0,head.end())+policy+html.substring(head.end()):policy+html;
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        web.loadDataWithBaseURL("https://preview-"+UUID.randomUUID()+".invalid/",safe,"text/html","UTF-8",null);
    }
    private boolean textFormat(String mime,String name){if(mime.startsWith("text/")||mime.equals("application/json")||mime.equals("image/svg+xml"))return true;for(String ext:new String[]{".md",".txt",".csv",".json",".log",".js",".css",".xml",".html",".htm",".svg"})if(name.endsWith(ext))return true;return false;}
    private void error(Exception e){runOnUiThread(()->{if(dead)return;status.setText(e.getMessage()==null?"暂时无法预览，请导出后查看":e.getMessage());});}
    private Button button(String label){Button v=new Button(this);v.setText(label);v.setTextSize(12);v.setTextColor(Color.rgb(23,23,23));v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(245,245,245)));return v;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onDestroy(){dead=true;executor.shutdownNow();if(web!=null){root.removeView(web);web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
