package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@android.annotation.TargetApi(29)
@RunWith(AndroidJUnit4.class)
public class WorkbenchTest {
    private Context context;
    private final Map<String,byte[]> original=new HashMap<>();
    private String account,other;
    private static final String[] STATE_FILES={"workspace.json","workspace.json.bak","workspace.json.new","workspace.backup.json","workspace.backup.json.bak","workspace.backup.json.new"};
    @Before public void setup()throws Exception{
        Assume.assumeTrue("Only explicit isolated fixture runs modify account fixtures","true".equals(InstrumentationRegistry.getArguments().getString("fixtureMode")));
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=29);
        context=ApplicationProvider.getApplicationContext();assertTrue(context.getPackageName().endsWith(".debug"));
        for(String name:STATE_FILES){File f=new File(context.getFilesDir(),name);if(f.isFile())original.put(name,Files.readAllBytes(f.toPath()));}
        WorkspaceState state=WorkspaceState.fresh();account=state.current().id;state.current().name="本地验收账号";
        other=state.addAccount("另一个测试账号","fixture@example.test",WorkspaceState.COLORS[1]).id;
        new StateStore(context.getFilesDir()).save(state);
    }
    @After public void restore()throws Exception{
        if(context==null)return;
        for(String name:STATE_FILES){File f=new File(context.getFilesDir(),name);if(original.containsKey(name))Files.write(f.toPath(),original.get(name));else Files.deleteIfExists(f.toPath());}
    }
    private Intent screen(Class<?> activity){return new Intent(context,activity).putExtra(AssetWorkbenchActivity.ACCOUNT,account);}
    private interface Condition{boolean value()throws Exception;}
    private void until(Condition condition)throws Exception{
        until(20000,condition);
    }
    private void until(long milliseconds,Condition condition)throws Exception{
        long stop=SystemClock.elapsedRealtime()+milliseconds;while(SystemClock.elapsedRealtime()<stop){if(condition.value())return;SystemClock.sleep(150);}fail("Native fixture did not become ready");
    }
    private <T extends View>T find(View root,Class<T> type){
        if(type.isInstance(root))return type.cast(root);
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){T result=find(group.getChildAt(i),type);if(result!=null)return result;}}
        return null;
    }
    private void screenshot(String name)throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(500);
        AtomicReference<android.view.accessibility.AccessibilityNodeInfo> active=new AtomicReference<>();
        until(()->{active.set(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());return active.get()!=null;});
        assertEquals(context.getPackageName(),String.valueOf(active.get().getPackageName()));
        Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
        ContentValues values=new ContentValues();
        String filename=name.replace(".png","-"+image.getWidth()+"x"+image.getHeight()+".png");
        values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,filename);
        values.put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");
        values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS+"/ProfilesFixtureEvidence");
        values.put(android.provider.MediaStore.Downloads.IS_PENDING,1);
        Uri destination=context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);assertNotNull(destination);
        try(OutputStream output=context.getContentResolver().openOutputStream(destination)){assertNotNull(output);assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}
        values.clear();values.put(android.provider.MediaStore.Downloads.IS_PENDING,0);context.getContentResolver().update(destination,values,null,null);image.recycle();

    }
    private String htmlBody(ActivityScenario<AssetPreviewActivity> scene,String shot)throws Exception{
        AtomicBoolean ready=new AtomicBoolean();until(()->{scene.onActivity(a->{WebView web=a.getWindow().getDecorView().findViewWithTag("asset-html-preview");ready.set(web!=null&&web.getProgress()==100&&!web.getSettings().getJavaScriptEnabled()&&!web.getSettings().getAllowFileAccess()&&!web.getSettings().getAllowContentAccess());});return ready.get();});
        screenshot(shot);
        AtomicReference<String> body=new AtomicReference<>();AtomicBoolean authorScriptRan=new AtomicBoolean(true);
        // Evaluate only after asserting and capturing the production static page.
        scene.onActivity(a->{WebView web=a.getWindow().getDecorView().findViewWithTag("asset-html-preview");web.getSettings().setJavaScriptEnabled(true);web.evaluateJavascript("JSON.stringify({body:document.body ? document.body.innerText : '',ran:window.shouldNotExecute===true})",value->{web.getSettings().setJavaScriptEnabled(false);try{JSONObject result=new JSONObject(String.valueOf(new JSONTokener(value).nextValue()));authorScriptRan.set(result.getBoolean("ran"));body.set(result.getString("body"));}catch(JSONException e){body.set("Invalid DOM result: "+value);}});});
        until(()->body.get()!=null);assertFalse("HTML author script executed",authorScriptRan.get());return body.get();
    }
    private void tapNative(String label)throws Exception{
        until(()->{
            android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root==null)return false;assertEquals(context.getPackageName(),String.valueOf(root.getPackageName()));
            for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)){
                if(!label.contentEquals(node.getText()==null?"":node.getText()))continue;
                tapNode(node);return true;
            }
            return false;
        });
    }
    private void tapNode(android.view.accessibility.AccessibilityNodeInfo node){
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);assertFalse(bounds.isEmpty());long down=SystemClock.uptimeMillis();
        MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);press.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(press,true));}finally{press.recycle();}
        SystemClock.sleep(80);MotionEvent release=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);release.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(release,true));}finally{release.recycle();}
    }
    private void spinnerNodes(android.view.accessibility.AccessibilityNodeInfo node,List<android.view.accessibility.AccessibilityNodeInfo> found){
        if("android.widget.Spinner".equals(String.valueOf(node.getClassName())))found.add(node);
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null)spinnerNodes(child,found);}
    }
    private void chooseFormat(String label)throws Exception{
        until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;assertEquals(context.getPackageName(),String.valueOf(root.getPackageName()));List<android.view.accessibility.AccessibilityNodeInfo> found=new ArrayList<>();spinnerNodes(root,found);if(found.isEmpty())return false;tapNode(found.get(0));return true;});tapNative(label);
    }
    private JSONObject throughRenderMenu(ActivityScenario<AssetWorkbenchActivity> scene,AssetLedger ledger,JSONObject source,String format)throws Exception{
        String id=source.getString("id");AtomicBoolean ready=new AtomicBoolean();
        until(()->{scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);ready.set(list.getAdapter()!=null&&list.getAdapter().getCount()==ledgerSize(ledger));});return ready.get();});
        scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);for(int i=0;i<list.getAdapter().getCount();i++)if(((JSONObject)list.getAdapter().getItem(i)).optString("id").equals(id)){assertTrue(list.performItemClick(list.getChildAt(i),i,list.getAdapter().getItemId(i)));return;}fail("Source asset not visible");});
        tapNative("转换格式");if(ConversionType.of(source).equals("markdown"))tapNative("PDF");
        String initial=ConversionType.of(source).equals("pdf")?"PNG":RenderTasks.formats(RenderTasks.kind(source)).get(0);if(!format.equals(initial))chooseFormat(format);
        Set<String> before=new HashSet<>();for(JSONObject row:ledger.list())before.add(row.getString("id"));tapNative("开始转换");
        String mime=format.equals("PDF")?"application/pdf":"image/"+(format.equals("JPEG")?"jpeg":format.toLowerCase(Locale.ROOT));
        try{until(90000,()->ledger.list().stream().anyMatch(a->!before.contains(a.optString("id"))&&id.equals(a.optString("parentId"))&&a.optString("status").equals("ready")&&a.optString("mime").equals(mime)));}
        catch(AssertionError error){AtomicReference<String> text=new AtomicReference<>();scene.onActivity(a->text.set(viewText(a.getWindow().getDecorView())));screenshot("android-render-failed-"+format+".png");throw new AssertionError("Render result missing: "+text.get(),error);}
        return ledger.list().stream().filter(a->!before.contains(a.optString("id"))&&id.equals(a.optString("parentId"))&&a.optString("mime").equals(mime)).findFirst().get();
    }
    private String viewText(View root){StringBuilder result=new StringBuilder();if(root instanceof TextView)result.append(((TextView)root).getText()).append('\n');if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.append(viewText(((ViewGroup)root).getChildAt(i)));return result.toString();}
    private int ledgerSize(AssetLedger ledger){try{return ledger.list().size();}catch(Exception e){throw new RuntimeException(e);}}
    private void rendererExits()throws Exception{
        until(()->{try(android.os.ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pidof "+context.getPackageName()+":asset_renderer");InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){return input.read()==-1;}});
    }
    @Test public void realDocumentUrisImportToTheInitiatingAccountAndPreviewAllThreeKinds()throws Exception{
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){
            // Deliver the real provider result immediately, also testing the initial-load queue.
            Intent documents=new Intent();documents.setClipData(ClipData.newUri(context.getContentResolver(),"fixture",Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/html")));
            documents.getClipData().addItem(new ClipData.Item(Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/image")));
            documents.getClipData().addItem(new ClipData.Item(Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/pdf")));
            scene.onActivity(a->a.onActivityResult(10,Activity.RESULT_OK,documents));
            until(()->{List<JSONObject> rows=new AssetLedger(context.getFilesDir(),account).list();return rows.size()==3&&rows.stream().allMatch(a->"ready".equals(a.optString("status")));});
            assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());
            AtomicInteger count=new AtomicInteger();until(()->{scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);count.set(list.getAdapter()==null?-1:list.getAdapter().getCount());});return count.get()==3;});
            screenshot("android-assets-native.png");
        }
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);
        for(JSONObject asset:ledger.list()){
            Intent preview=screen(AssetPreviewActivity.class).putExtra("asset",asset.getString("id"));
            try(ActivityScenario<AssetPreviewActivity> scene=ActivityScenario.launch(preview)){
                if(asset.getString("mime").equals("text/html")){
                    String body=htmlBody(scene,"android-html-preview.png");assertTrue("Actual HTML body: "+body,body.contains("本地 HTML 预览"));
                }else{
                    AtomicBoolean ready=new AtomicBoolean();until(()->{scene.onActivity(a->{ImageView image=find(a.getWindow().getDecorView(),ImageView.class);ready.set(image!=null&&image.getDrawable() instanceof BitmapDrawable);});return ready.get();});
                    if(asset.getString("mime").equals("image/png"))scene.onActivity(a->{Bitmap image=((BitmapDrawable)find(a.getWindow().getDecorView(),ImageView.class).getDrawable()).getBitmap();assertEquals(8,image.getWidth());assertEquals(6,image.getHeight());});
                    screenshot(asset.getString("mime").equals("application/pdf")?"android-pdf-preview.png":"android-image-preview.png");
                }
            }
        }
    }
    @Test public void allProjectSkillsAreVisibleAndNativeSearchFiltersThem()throws Exception{
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class).putExtra("skills",true))){
            AtomicInteger count=new AtomicInteger();
            until(()->{scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);count.set(list.getAdapter()==null?-1:list.getAdapter().getCount());});return count.get()==120;});
            screenshot("android-skills-native.png");scene.onActivity(a->find(a.getWindow().getDecorView(),EditText.class).setText("HTML"));
            until(()->{scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);count.set(list.getAdapter().getCount());});return count.get()>0&&count.get()<120;});
        }
    }

    @Test public void nativeImageConversionsProduceRealCopiesAndKeepSourceBytes()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);
        JSONObject source;
        try(InputStream input=context.getContentResolver().openInputStream(Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/image"))){source=ledger.importCopy(input,"source.png","image/png","import",null);}
        String id=source.getString("id"),hash=source.getString("sha256");
        for(String format:new String[]{"png","jpeg","webp"}){
            JSONObject output=ImageConversions.convert(context,ledger,id,format,0,90,()->WorkspaceFiles.contains(context.getFilesDir(),account));
            assertEquals(id,output.getString("parentId"));assertEquals(account,output.getString("accountId"));assertEquals("conversion",output.getString("source"));
            File file=ledger.verifiedFile(output.getString("id"));Bitmap bitmap=BitmapFactory.decodeFile(file.getPath());assertNotNull(bitmap);assertEquals(8,bitmap.getWidth());assertEquals(6,bitmap.getHeight());bitmap.recycle();
            byte[] bytes=Files.readAllBytes(file.toPath());
            if(format.equals("png")){assertEquals(0x89,bytes[0]&255);assertEquals('P',bytes[1]);}
            if(format.equals("jpeg")){assertEquals(0xff,bytes[0]&255);assertEquals(0xd8,bytes[1]&255);}
            if(format.equals("webp")){assertEquals("RIFF",new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII));assertEquals("WEBP",new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII));}
        }
        assertEquals(hash,ledger.get(id).getString("sha256"));ledger.verifiedFile(id);assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());
    }
    @Test public void nativeDocumentMenuProducesOwnedJsonAndRendersItsActualContent()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);
        byte[] original="code,说明\n00123,移动端文档转换\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JSONObject source=ledger.importCopy(new ByteArrayInputStream(original),"fixture.csv","text/csv","import",null);
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){
            AtomicBoolean ready=new AtomicBoolean();until(()->{scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);ready.set(list.getAdapter()!=null&&list.getAdapter().getCount()==1);});return ready.get();});
            scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);assertTrue(list.performItemClick(list.getChildAt(0),0,list.getAdapter().getItemId(0)));});
            tapNative("转换格式");screenshot("android-document-conversion.png");tapNative("开始转换");
            until(()->ledger.list().size()==2&&ledger.list().stream().allMatch(a->a.optString("status").equals("ready")));
            screenshot("android-converted-documents.png");
        }
        JSONObject output=ledger.list().stream().filter(a->a.has("parentId")).findFirst().get();
        assertEquals(source.getString("id"),output.getString("parentId"));assertEquals(account,output.getString("accountId"));assertEquals("application/json",output.getString("mime"));
        JSONArray rows=new JSONArray(new String(Files.readAllBytes(ledger.verifiedFile(output.getString("id")).toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals("00123",rows.getJSONObject(0).getString("code"));
        assertArrayEquals(original,Files.readAllBytes(ledger.verifiedFile(source.getString("id")).toPath()));assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());
        try(ActivityScenario<AssetPreviewActivity> scene=ActivityScenario.launch(screen(AssetPreviewActivity.class).putExtra("asset",output.getString("id")))){
            AtomicBoolean visible=new AtomicBoolean();until(()->{scene.onActivity(a->visible.set(containsText(a.getWindow().getDecorView(),"00123")));return visible.get();});screenshot("android-json-preview.png");
        }
    }
    private boolean containsText(View root,String text){
        if(root instanceof TextView&&((TextView)root).getText().toString().contains(text))return true;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(containsText(((ViewGroup)root).getChildAt(i),text))return true;
        return false;
    }
    @Test public void markdownConvertsToRealStaticHtmlAndPreviewShowsItsBody()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);
        JSONObject source=ledger.importCopy(new ByteArrayInputStream("# 移动报告\n\n|项目|结果|\n|---|---|\n|文档转换|已完成|\n\n- [x] 保留原件\n\n<script>window.shouldNotExecute=true</script>\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"report.md","text/markdown","import",null);
        JSONObject result=DocumentConversions.convert(context.getCacheDir(),ledger,source.getString("id"),"HTML",null,()->WorkspaceFiles.contains(context.getFilesDir(),account));
        String html=new String(Files.readAllBytes(ledger.verifiedFile(result.getString("id")).toPath()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("<table>"));assertTrue(html.contains("<h1>移动报告</h1>"));assertFalse(html.contains("<script>"));
        assertEquals(account,result.getString("accountId"));assertEquals(source.getString("id"),result.getString("parentId"));assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());
        try(ActivityScenario<AssetPreviewActivity> scene=ActivityScenario.launch(screen(AssetPreviewActivity.class).putExtra("asset",result.getString("id")))){
            String body=htmlBody(scene,"android-markdown-html-preview.png");assertTrue(body.contains("移动报告"));assertTrue(body.contains("文档转换"));assertTrue(body.contains("已完成"));
        }
    }
    @Test public void wholeHtmlPngAndMultipagePdfContainBothEndsAndKeepOwner()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);String html="<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;padding:0}div{height:1200px}</style></head><body><div style=\"background:#ff0000\"></div><div style=\"background:#0000ff\"></div></body></html>";
        JSONObject source=ledger.importCopy(new ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"long.html","text/html","import",null),png,pdf;
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){png=throughRenderMenu(scene,ledger,source,"PNG");pdf=throughRenderMenu(scene,ledger,source,"PDF");}
        Bitmap image=BitmapFactory.decodeFile(ledger.verifiedFile(png.getString("id")).getPath());assertNotNull(image);assertEquals(1280,image.getWidth());assertTrue("Long document was truncated",image.getHeight()>2000);assertRed(image.getPixel(image.getWidth()/2,image.getHeight()/10));assertBlue(image.getPixel(image.getWidth()/2,image.getHeight()*9/10));image.recycle();
        try(android.os.ParcelFileDescriptor descriptor=android.os.ParcelFileDescriptor.open(ledger.verifiedFile(pdf.getString("id")),android.os.ParcelFileDescriptor.MODE_READ_ONLY);android.graphics.pdf.PdfRenderer document=new android.graphics.pdf.PdfRenderer(descriptor)){
            assertTrue(document.getPageCount()>1);assertTrue(pdfHasColor(document,0,true));assertTrue(pdfHasColor(document,document.getPageCount()-1,false));
        }
        assertEquals(source.getString("sha256"),ledger.get(source.getString("id")).getString("sha256"));assertEquals(account,png.getString("accountId"));assertEquals(account,pdf.getString("accountId"));assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());
        try(ActivityScenario<AssetPreviewActivity> scene=ActivityScenario.launch(screen(AssetPreviewActivity.class).putExtra("asset",png.getString("id")))){AtomicBoolean visible=new AtomicBoolean();until(()->{scene.onActivity(a->visible.set(find(a.getWindow().getDecorView(),ImageView.class)!=null));return visible.get();});screenshot("android-html-long-image.png");}rendererExits();
    }
    private void assertRed(int pixel){assertTrue("Expected red",Color.red(pixel)>220&&Color.green(pixel)<35&&Color.blue(pixel)<35);}
    private void assertBlue(int pixel){assertTrue("Expected blue",Color.blue(pixel)>220&&Color.green(pixel)<35&&Color.red(pixel)<35);}
    private boolean pdfHasColor(android.graphics.pdf.PdfRenderer pdf,int number,boolean red){
        try(android.graphics.pdf.PdfRenderer.Page page=pdf.openPage(number)){Bitmap bitmap=Bitmap.createBitmap(page.getWidth(),page.getHeight(),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);boolean found=false;for(int y=0;y<bitmap.getHeight();y+=8)for(int x=0;x<bitmap.getWidth();x+=8){int p=bitmap.getPixel(x,y);if(red?Color.red(p)>220&&Color.green(p)<35&&Color.blue(p)<35:Color.blue(p)>220&&Color.green(p)<35&&Color.red(p)<35)found=true;}bitmap.recycle();return found;}
    }
    @Test public void svgFormatsPreserveShapeAlphaAndJpegWhiteBackground()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);String svg="<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\" viewBox=\"0 0 40 20\"><rect x=\"10\" y=\"0\" width=\"20\" height=\"20\" fill=\"#00ff00\"/></svg>";
        JSONObject source=ledger.importCopy(new ByteArrayInputStream(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"shape.svg","image/svg+xml","import",null);
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){
            for(String format:new String[]{"PNG","JPEG","WEBP"}){
                JSONObject result=throughRenderMenu(scene,ledger,source,format);Bitmap image=BitmapFactory.decodeFile(ledger.verifiedFile(result.getString("id")).getPath());assertNotNull(image);assertEquals(1280,image.getWidth());assertEquals(640,image.getHeight());
                int center=image.getPixel(640,320),corner=image.getPixel(5,5);assertTrue(Color.green(center)>220&&Color.red(center)<35&&Color.blue(center)<35);
                if(format.equals("JPEG")){assertTrue(Color.red(corner)>240&&Color.green(corner)>240&&Color.blue(corner)>240);}else assertEquals(0,Color.alpha(corner));image.recycle();
            }
        }
        assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());ledger.verifiedFile(source.getString("id"));rendererExits();
    }
    @Test public void markdownPdfIsRealAndPdfSelectionsUseRequestedPagesWithoutChangingSource()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);JSONObject markdown=ledger.importCopy(new ByteArrayInputStream("# 本地 PDF 报告\n\n|项目|状态|\n|---|---|\n|转换|完成|\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"report.md","text/markdown","import",null),converted;
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){converted=throughRenderMenu(scene,ledger,markdown,"PDF");}
        try(android.os.ParcelFileDescriptor descriptor=android.os.ParcelFileDescriptor.open(ledger.verifiedFile(converted.getString("id")),android.os.ParcelFileDescriptor.MODE_READ_ONLY);android.graphics.pdf.PdfRenderer document=new android.graphics.pdf.PdfRenderer(descriptor)){
            assertTrue(document.getPageCount()>=1);try(android.graphics.pdf.PdfRenderer.Page page=document.openPage(0)){Bitmap rendered=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888);rendered.eraseColor(Color.WHITE);page.render(rendered,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);int ink=0;for(int y=0;y<842;y+=4)for(int x=0;x<595;x+=4)if(Color.red(rendered.getPixel(x,y))<200)ink++;rendered.recycle();assertTrue("Markdown PDF is blank",ink>30);}
        }
        android.graphics.pdf.PdfDocument document=new android.graphics.pdf.PdfDocument();for(int i=0;i<2;i++){android.graphics.pdf.PdfDocument.Page page=document.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(100,100,i+1).create());page.getCanvas().drawColor(i==0?Color.RED:Color.BLUE);document.finishPage(page);}ByteArrayOutputStream bytes=new ByteArrayOutputStream();document.writeTo(bytes);document.close();
        JSONObject source=ledger.importCopy(new ByteArrayInputStream(bytes.toByteArray()),"pages.pdf","application/pdf","import",null);
        List<JSONObject> output=RenderedConversions.pdfPages(context,ledger,source.getString("id"),PageSelection.parse("2,1"),"PNG",640,90,()->WorkspaceFiles.contains(context.getFilesDir(),account));assertEquals(2,output.size());
        for(int i=0;i<2;i++){Bitmap image=BitmapFactory.decodeFile(ledger.verifiedFile(output.get(i).getString("id")).getPath());assertEquals(640,image.getWidth());assertEquals(640,image.getHeight());if(i==0)assertBlue(image.getPixel(320,320));else assertRed(image.getPixel(320,320));image.recycle();assertEquals(source.getString("id"),output.get(i).getString("parentId"));}
        int before=ledger.list().size();assertThrows(Exception.class,()->RenderedConversions.pdfPages(context,ledger,source.getString("id"),Arrays.asList(1,3),"PNG",640,90,()->true));assertEquals(before,ledger.list().size());
        JSONObject jpeg=RenderedConversions.pdfPages(context,ledger,source.getString("id"),Collections.singletonList(1),"JPEG",640,90,()->true).get(0);Bitmap image=BitmapFactory.decodeFile(ledger.verifiedFile(jpeg.getString("id")).getPath());assertRed(image.getPixel(320,320));image.recycle();
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){JSONObject ui=throughRenderMenu(scene,ledger,source,"PNG");assertTrue(ui.getString("name").endsWith("-p1.png"));screenshot("android-pdf-page-assets.png");}
        assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());assertArrayEquals(bytes.toByteArray(),Files.readAllBytes(ledger.verifiedFile(source.getString("id")).toPath()));rendererExits();
    }
}
