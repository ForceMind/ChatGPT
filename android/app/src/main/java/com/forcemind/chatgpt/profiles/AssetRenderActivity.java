package com.forcemind.chatgpt.profiles;

import android.app.Application;
import android.content.Intent;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.os.*;
import android.util.Base64;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Private temporary process; only the parent may publish to an account ledger. */
public final class AssetRenderActivity extends ComponentActivity {
    private static boolean configured;
    private static int activeJobs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile boolean dead,done;
    private boolean registered;
    private String token,format,kind;
    private int width,quality,documentHeight,pagePixels,pageCount,pageIndex;
    private WebView web;
    private TextView status;
    private PdfDocument pdf;
    private final Runnable timeout=()->fail("转换超时，原文件已保留。请缩小页面后重试。");
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(!Application.getProcessName().endsWith(":asset_renderer")){fail("转换进程配置无效");return;}
        if(activeJobs!=0){fail("已有页面转换进行中，请等待完成");return;}
        activeJobs++;registered=true;
        if(!configured){WebView.setDataDirectorySuffix("asset-renderer");WebView.enableSlowWholeDocumentDraw();configured=true;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(dp(16)+bars.left,bars.top,dp(16)+bars.right,bars.bottom);return insets;});
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);root.addView(top,new LinearLayout.LayoutParams(-1,dp(56)));
        Button cancel=new Button(this);cancel.setText("取消转换");cancel.setTextColor(Color.BLACK);cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(245,245,245)));top.addView(cancel);cancel.setOnClickListener(v->fail("转换已取消，原文件保留"));
        status=new TextView(this);status.setText("正在准备本机转换…");status.setTextColor(Color.rgb(23,23,23));status.setPadding(0,dp(12),0,dp(12));root.addView(status);
        FrameLayout frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        token=getIntent().getStringExtra("task");handler.postDelayed(timeout,60000);
        executor.execute(()->{
            try{
                JSONObject request=RenderTasks.request(getCacheDir(),token);String input=RenderTasks.source(getCacheDir(),token,request);
                kind=request.getString("kind");format=request.getString("format");width=request.getInt("width");quality=request.getInt("quality");
                String html=input;
                if(kind.equals("markdown")){StringWriter out=new StringWriter();DocumentFormats.markdownHtml(input,out);html=out.toString();}
                if(kind.equals("svg"))html="<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;padding:0;background:transparent}img{display:block;width:100%;height:auto}</style></head><body><img alt=\"\" src=\"data:image/svg+xml;base64,"+Base64.encodeToString(input.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)+"\"></body></html>";
                String content=html,owner=request.optString("owner");runOnUiThread(()->{
                    if(dead||done)return;status.setText(owner+"\n正在生成 "+format+" 副本，外部资源不可用…");
                    web=new WebView(this);web.setTag("asset-render-web");web.setBackgroundColor(kind.equals("svg")?Color.TRANSPARENT:Color.WHITE);
                    frame.addView(web,new FrameLayout.LayoutParams(width,900));
                    OfflineHtml.load(web,content,()->{if(web!=null&&!dead&&!done)web.postVisualStateCallback(1,new WebView.VisualStateCallback(){@Override public void onComplete(long id){if(!dead&&!done)begin();}});},this::fail);
                });
            }catch(Exception|OutOfMemoryError|StackOverflowError e){fail("无法准备页面转换，原文件保留。"+(e.getMessage()==null?"":e.getMessage()));}
        });
    }
    @SuppressWarnings("deprecation")
    private int contentHeight(){double height=Math.ceil(web.getContentHeight()*(double)web.getScale());return Double.isFinite(height)&&height<=Integer.MAX_VALUE?(int)height:-1;}
    private void begin(){
        if(dead||done)return;
        try{
            documentHeight=contentHeight();if(documentHeight<1){handler.postDelayed(this::begin,100);return;}
            if(format.equals("PDF")){beginPdf();return;}
            if(documentHeight>RenderTasks.MAX_HEIGHT||(long)width*documentHeight>RenderTasks.MAX_PIXELS)throw new IOException("页面过长，请选择 PDF 或较小宽度（图片上限 1600 万像素）");
            Bitmap image=Bitmap.createBitmap(width,documentHeight,Bitmap.Config.ARGB_8888);
            try{Canvas canvas=new Canvas(image);canvas.drawColor(format.equals("JPEG")||!kind.equals("svg")?Color.WHITE:Color.TRANSPARENT);web.scrollTo(0,0);web.draw(canvas);}
            catch(Throwable e){image.recycle();throw e;}
            executor.execute(()->{
                try{
                    Bitmap.CompressFormat codec=format.equals("JPEG")?Bitmap.CompressFormat.JPEG:format.equals("WEBP")?Bitmap.CompressFormat.WEBP:Bitmap.CompressFormat.PNG;
                    try(FileOutputStream out=output();OutputStream bounded=bounded(out)){if(!image.compress(codec,quality,bounded))throw new IOException("图片编码未完成");bounded.flush();out.getFD().sync();}
                    image.recycle();complete();
                }catch(Exception|OutOfMemoryError e){if(!image.isRecycled())image.recycle();fail("图片转换未完成，原件保留。"+message(e));}
            });
        }catch(Exception|OutOfMemoryError e){fail("页面转换未完成，原件保留。"+message(e));}
    }
    private void beginPdf(){
        // Public PdfDocument API; no hidden print callbacks or system print job.
        double scale=515d/width;pagePixels=(int)Math.floor(762d/scale);
        web.setLayoutParams(new FrameLayout.LayoutParams(width,pagePixels));web.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(pagePixels,View.MeasureSpec.EXACTLY));web.layout(0,0,width,pagePixels);
        web.postVisualStateCallback(2,new WebView.VisualStateCallback(){@Override public void onComplete(long id){
            if(dead||done)return;documentHeight=contentHeight();pageCount=(int)Math.ceil(documentHeight/(double)pagePixels);
            if(documentHeight<1||pageCount<1||pageCount>100){fail("PDF 页数超过本机转换范围（最多 100 页），请拆分文件");return;}
            pdf=new PdfDocument();pageIndex=0;pdfPage();
        }});
    }
    private void pdfPage(){
        if(dead||done||pdf==null)return;
        int offset=pageIndex*pagePixels,expected=Math.min(offset,Math.max(0,documentHeight-pagePixels));web.scrollTo(0,expected);
        web.postVisualStateCallback(10+pageIndex,new WebView.VisualStateCallback(){@Override public void onComplete(long id){
            if(dead||done||pdf==null)return;
            try{
                if(Math.abs(web.getScrollY()-expected)>2||Math.abs(contentHeight()-documentHeight)>2)throw new IOException("页面布局不稳定，请简化布局后转换");
                status.setText("正在生成 PDF · "+(pageIndex+1)+" / "+pageCount);
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,pageIndex+1).create());
                try{Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);canvas.save();canvas.clipRect(40,40,555,802);canvas.translate(40,40);canvas.scale(515f/width,515f/width);canvas.translate(0,-(offset-web.getScrollY()));web.draw(canvas);canvas.restore();}finally{pdf.finishPage(page);}
                if(++pageIndex<pageCount){handler.post(AssetRenderActivity.this::pdfPage);return;}
                PdfDocument document=pdf;pdf=null;
                executor.execute(()->{try{
                    try(FileOutputStream out=output();OutputStream stream=bounded(out)){document.writeTo(stream);stream.flush();out.getFD().sync();}
                    document.close();complete();
                }catch(Exception|OutOfMemoryError e){try{document.close();}catch(Exception ignored){}fail("PDF 生成未完成，原件保留。"+message(e));}});
            }catch(Exception|OutOfMemoryError e){fail("PDF 排版未完成，原件保留。"+message(e));}
        }});
    }
    private FileOutputStream output()throws Exception{
        if(dead||done||Thread.currentThread().isInterrupted())throw new InterruptedIOException("转换已取消");
        File file=new File(RenderTasks.directory(getCacheDir(),token,false),"result.partial");Files.createFile(file.toPath());return new FileOutputStream(file);
    }
    private OutputStream bounded(OutputStream destination){return new FilterOutputStream(destination){
        private long size;
        private void check(int n)throws IOException{if(dead||done||Thread.currentThread().isInterrupted())throw new InterruptedIOException("转换已取消");size+=n;if(size>RenderTasks.MAX_RESULT)throw new IOException("转换结果超过 64 MiB，请缩小页面");}
        @Override public void write(int value)throws IOException{check(1);out.write(value);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException{check(length);out.write(bytes,offset,length);}
    };}
    private void complete()throws Exception{
        if(dead||done||Thread.currentThread().isInterrupted())throw new InterruptedIOException("转换已取消");
        File directory=RenderTasks.directory(getCacheDir(),token,false);Files.move(new File(directory,"result.partial").toPath(),new File(directory,"result.bin").toPath(),StandardCopyOption.ATOMIC_MOVE);
        runOnUiThread(()->{if(dead||done)return;done=true;handler.removeCallbacks(timeout);setResult(RESULT_OK,new Intent().putExtra("task",token));finish();});
    }
    private static String message(Throwable e){return e.getMessage()==null?"请缩小文件或重试":e.getMessage();}
    private void fail(String error){runOnUiThread(()->{if(dead||done)return;done=true;handler.removeCallbacks(timeout);setResult(RESULT_CANCELED,new Intent().putExtra("task",token).putExtra("error",error));finish();});}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onDestroy(){
        dead=true;handler.removeCallbacksAndMessages(null);executor.shutdownNow();if(pdf!=null){try{pdf.close();}catch(Exception ignored){}pdf=null;}
        if(web!=null){if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);web.stopLoading();web.destroy();web=null;}
        if(registered)activeJobs--;super.onDestroy();
        if(Application.getProcessName().endsWith(":asset_renderer"))new Handler(Looper.getMainLooper()).postDelayed(()->{if(activeJobs==0)android.os.Process.killProcess(android.os.Process.myPid());},1000);
    }
}
