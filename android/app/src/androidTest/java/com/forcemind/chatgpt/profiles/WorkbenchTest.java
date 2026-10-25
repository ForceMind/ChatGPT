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
import java.nio.charset.StandardCharsets;
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
    private void selectNativeFile(String label)throws Exception{
        until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;
            for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(node.getText()==null?"":node.getText())){
                if(node.isChecked())return true;assertTrue("File checkbox action unavailable: "+label,node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));return true;
            }return false;
        });
        until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(node.getText()==null?"":node.getText())&&node.isChecked())return true;return false;});
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
        chooseSpinner(0,label);
    }
    private void chooseSpinner(int index,String label)throws Exception{
        AtomicReference<Rect> previous=new AtomicReference<>();
        try{
            until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;assertEquals(context.getPackageName(),String.valueOf(root.getPackageName()));List<android.view.accessibility.AccessibilityNodeInfo> found=new ArrayList<>();spinnerNodes(root,found);if(found.size()<=index||!found.get(index).isVisibleToUser()||!found.get(index).isEnabled())return false;Rect bounds=new Rect();found.get(index).getBoundsInScreen(bounds);Rect old=previous.getAndSet(bounds);if(bounds.isEmpty()||!bounds.equals(old))return false;tapNode(found.get(index));return true;});tapNative(label);
            until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;List<android.view.accessibility.AccessibilityNodeInfo> found=new ArrayList<>();spinnerNodes(root,found);if(found.size()<=index)return false;for(android.view.accessibility.AccessibilityNodeInfo text:found.get(index).findAccessibilityNodeInfosByText(label))if(label.contentEquals(text.getText()==null?"":text.getText()))return true;return false;});
        }catch(AssertionError error){try{screenshot("android-spinner-failed-"+index+".png");}catch(Exception ignored){}throw new AssertionError("Native spinner selection failed: "+label,error);}
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
    private void encodedEvidence(String name,String mime,InputStream input)throws Exception{
        android.util.DisplayMetrics metrics=context.getResources().getDisplayMetrics();int dot=name.lastIndexOf('.');String filename=name.substring(0,dot)+"-"+metrics.widthPixels+"x"+metrics.heightPixels+name.substring(dot);
        ContentValues values=new ContentValues();values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,filename);values.put(android.provider.MediaStore.Downloads.MIME_TYPE,mime);values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS+"/ProfilesFixtureEvidence");values.put(android.provider.MediaStore.Downloads.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);assertNotNull(uri);
        try(OutputStream out=context.getContentResolver().openOutputStream(uri)){assertNotNull(out);byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);}
        values.clear();values.put(android.provider.MediaStore.Downloads.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);
    }
    private void assetEvidence(AssetLedger ledger,JSONObject asset,String name)throws Exception{try(InputStream input=Files.newInputStream(ledger.verifiedFile(asset.getString("id")).toPath())){encodedEvidence(name,asset.getString("mime"),input);}}
    @Test public void websiteShowsActualNativeAssetsWithExplicitDemoFiles()throws Exception{
        WorkspaceState state=new StateStore(context.getFilesDir()).load();state.current().name="演示账号";new StateStore(context.getFilesDir()).save(state);
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){
            Intent documents=new Intent();documents.setClipData(ClipData.newUri(context.getContentResolver(),"demo",Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/html")));
            documents.getClipData().addItem(new ClipData.Item(Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/image")));
            documents.getClipData().addItem(new ClipData.Item(Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/pdf")));
            scene.onActivity(a->a.onActivityResult(10,Activity.RESULT_OK,documents));
            until(()->new AssetLedger(context.getFilesDir(),account).list().size()==3);
            AtomicInteger count=new AtomicInteger();until(()->{scene.onActivity(a->count.set(find(a.getWindow().getDecorView(),ListView.class).getAdapter().getCount()));return count.get()==3;});
            SystemClock.sleep(4000); // Wait for the import toast to disappear.
            screenshot("android-website-assets.png");
        }
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class).putExtra("skills",true))){
            AtomicInteger count=new AtomicInteger();until(()->{scene.onActivity(a->count.set(find(a.getWindow().getDecorView(),ListView.class).getAdapter()==null?0:find(a.getWindow().getDecorView(),ListView.class).getAdapter().getCount()));return count.get()==120;});
            screenshot("android-website-skills.png");
        }
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
        assetEvidence(ledger,png,"android-html-long.png");assetEvidence(ledger,pdf,"android-html-pages.pdf");
        Bitmap image=BitmapFactory.decodeFile(ledger.verifiedFile(png.getString("id")).getPath());assertNotNull(image);assertEquals(1280,image.getWidth());assertTrue("Long document was truncated",image.getHeight()>2000);assertRed(image.getPixel(image.getWidth()/2,image.getHeight()/10));assertBlue(image.getPixel(image.getWidth()/2,image.getHeight()*9/10));int middle=image.getHeight()/2;image.recycle();
        try(android.os.ParcelFileDescriptor descriptor=android.os.ParcelFileDescriptor.open(ledger.verifiedFile(pdf.getString("id")),android.os.ParcelFileDescriptor.MODE_READ_ONLY);android.graphics.pdf.PdfRenderer document=new android.graphics.pdf.PdfRenderer(descriptor)){
            assertTrue("HTML PDF should span multiple pages: "+document.getPageCount(),document.getPageCount()>1);boolean first=pdfHasColor(document,0,true),last=pdfHasColor(document,document.getPageCount()-1,false);assertTrue("First PDF page has no red content",first);assertTrue("Last PDF page has no blue content",last);
            assertPdfPoint(document,middle-137,true);assertPdfPoint(document,middle+137,false);
        }
        assertEquals(source.getString("sha256"),ledger.get(source.getString("id")).getString("sha256"));assertEquals(account,png.getString("accountId"));assertEquals(account,pdf.getString("accountId"));assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());
        try(ActivityScenario<AssetPreviewActivity> scene=ActivityScenario.launch(screen(AssetPreviewActivity.class).putExtra("asset",png.getString("id")))){AtomicBoolean visible=new AtomicBoolean();until(()->{scene.onActivity(a->visible.set(find(a.getWindow().getDecorView(),ImageView.class)!=null));return visible.get();});screenshot("android-html-long-image.png");}rendererExits();
    }
    private void assertRed(int pixel){assertTrue("Expected red",Color.red(pixel)>220&&Color.green(pixel)<35&&Color.blue(pixel)<35);}
    private void assertBlue(int pixel){assertTrue("Expected blue",Color.blue(pixel)>220&&Color.green(pixel)<35&&Color.red(pixel)<35);}
    private void assertPdfPoint(android.graphics.pdf.PdfRenderer pdf,int documentY,boolean red){
        int pagePixels=(int)Math.floor(762d*1280/515),index=documentY/pagePixels;
        try(android.graphics.pdf.PdfRenderer.Page page=pdf.openPage(index)){Bitmap bitmap=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);int color=bitmap.getPixel(298,(int)Math.round(40+(documentY%pagePixels)*515d/1280));bitmap.recycle();if(red)assertRed(color);else assertBlue(color);}
    }
    private boolean pdfHasColor(android.graphics.pdf.PdfRenderer pdf,int number,boolean red)throws Exception{
        try(android.graphics.pdf.PdfRenderer.Page page=pdf.openPage(number)){Bitmap bitmap=Bitmap.createBitmap(page.getWidth(),page.getHeight(),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);boolean found=false;for(int y=0;y<bitmap.getHeight();y+=8)for(int x=0;x<bitmap.getWidth();x+=8){int p=bitmap.getPixel(x,y);if(red?Color.red(p)>220&&Color.green(p)<35&&Color.blue(p)<35:Color.blue(p)>220&&Color.green(p)<35&&Color.red(p)<35)found=true;}ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();encodedEvidence("android-pdf-page-"+number+".png","image/png",new ByteArrayInputStream(bytes.toByteArray()));return found;}
    }
    @Test public void svgFormatsPreserveShapeAlphaAndJpegWhiteBackground()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);String svg="<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\" viewBox=\"0 0 40 20\"><rect x=\"10\" y=\"0\" width=\"20\" height=\"20\" fill=\"#00ff00\"/><script>document.querySelector('rect').setAttribute('fill','#ff0000')</script></svg>";
        JSONObject source=ledger.importCopy(new ByteArrayInputStream(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"shape.svg","image/svg+xml","import",null);
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){
            for(String format:new String[]{"PNG","JPEG","WEBP"}){
                JSONObject result=throughRenderMenu(scene,ledger,source,format);assetEvidence(ledger,result,"android-svg-"+format.toLowerCase(Locale.ROOT)+"."+(format.equals("JPEG")?"jpg":format.toLowerCase(Locale.ROOT)));Bitmap image=BitmapFactory.decodeFile(ledger.verifiedFile(result.getString("id")).getPath());assertNotNull(image);assertEquals(1280,image.getWidth());assertEquals(640,image.getHeight());
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
    @Test public void rendererFailureKeepsOriginalCleansTaskAndAllowsRetry()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);JSONObject source=ledger.importCopy(new ByteArrayInputStream(new byte[]{(byte)0xff}),"invalid.html","text/html","import",null);
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){
            AtomicBoolean ready=new AtomicBoolean();until(()->{scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);ready.set(list.getAdapter()!=null&&list.getAdapter().getCount()==1);});return ready.get();});
            for(int attempt=0;attempt<2;attempt++){
                scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);assertTrue(list.performItemClick(list.getChildAt(0),0,list.getAdapter().getItemId(0)));});tapNative("转换格式");tapNative("开始转换");
                AtomicBoolean error=new AtomicBoolean();until(90000,()->{scene.onActivity(a->error.set(containsText(a.getWindow().getDecorView(),"不是有效的 UTF-8")));return error.get();});assertEquals(1,ledger.list().size());
            }
            screenshot("android-render-error-retry.png");
        }
        File tasks=new File(context.getCacheDir(),"asset-render-tasks");assertNotNull(tasks.list());assertEquals(0,tasks.list().length);ledger.verifiedFile(source.getString("id"));rendererExits();
    }
    @Test public void publicBrowserInputsCarryChooserMarkerActualFilesAndNativeText()throws Exception{
        try(ActivityScenario<UploadProbeActivity> scene=ActivityScenario.launch(new Intent(context,UploadProbeActivity.class))){
            AtomicBoolean loaded=new AtomicBoolean();until(()->{scene.onActivity(a->loaded.set(a.web.getProgress()==100));return loaded.get();});
            AtomicReference<String> rectangle=new AtomicReference<>();scene.onActivity(a->a.web.evaluateJavascript("(function(){var i=document.querySelector('#upload');i.accept='application/octet-stream,"+a.marker+"';var r=i.getBoundingClientRect();return JSON.stringify({x:r.left+20,y:r.top+20})})()",rectangle::set));until(()->rectangle.get()!=null);
            JSONObject r=new JSONObject(String.valueOf(new JSONTokener(rectangle.get()).nextValue()));scene.onActivity(a->{try{WebInputs.tap(a.web,r.optDouble("x"),r.optDouble("y"));}catch(Exception e){throw new RuntimeException(e);}});
            AtomicInteger accepted=new AtomicInteger();until(()->{scene.onActivity(a->accepted.set(a.accepted));return accepted.get()==1;});
            AtomicReference<String> files=new AtomicReference<>();until(()->{scene.onActivity(a->a.web.evaluateJavascript("window.uploaded",files::set));return files.get()!=null&&!files.get().equals("null");});
            String uploaded=String.valueOf(new JSONTokener(files.get()).nextValue());assertTrue("Browser file read failed: "+uploaded,uploaded.startsWith("["));JSONArray actual=new JSONArray(uploaded);assertEquals(2,actual.length());
            for(int i=0;i<2;i++){String type=i==0?"html":"image";byte[] bytes;try(InputStream input=context.getContentResolver().openInputStream(Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/"+type))){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);bytes=out.toByteArray();}StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));assertEquals(bytes.length,actual.getJSONObject(i).getInt("size"));assertEquals(hash.toString(),actual.getJSONObject(i).getString("sha"));}
            AtomicBoolean focused=new AtomicBoolean();scene.onActivity(a->a.web.evaluateJavascript("document.querySelector('#prompt-textarea').focus();true",value->focused.set(true)));until(focused::get);
            AtomicBoolean inserted=new AtomicBoolean();scene.onActivity(a->inserted.set(WebInputs.insert(a.web,"离线任务输入验证")));assertTrue("Native input connection unavailable",inserted.get());
            AtomicReference<String> text=new AtomicReference<>();until(()->{scene.onActivity(a->a.web.evaluateJavascript("document.querySelector('#prompt-textarea').value",text::set));return text.get()!=null&&text.get().contains("离线任务输入验证");});
            scene.onActivity(a->{try{WebInputs.tap(a.web,25,180);}catch(Exception e){throw new RuntimeException(e);}});AtomicInteger rejected=new AtomicInteger();until(()->{scene.onActivity(a->{rejected.set(a.rejected);accepted.set(a.accepted);});return rejected.get()==1;});assertEquals(1,accepted.get());
            screenshot("android-offline-upload-probe.png");
        }
    }
    @Test public void assetDialogHandsActualFilesToTargetWebpageAndSendsOnce()throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),account);List<JSONObject> originals=new ArrayList<>();
        for(String kind:new String[]{"html","image"})try(InputStream input=context.getContentResolver().openInputStream(Uri.parse("content://"+FixtureFilesProvider.AUTHORITY+"/"+kind))){originals.add(ledger.importCopy(input,kind.equals("html")?"offline-report.html":"offline-image.png",kind.equals("html")?"text/html":"image/png","import",null));}
        MainActivity.handoffFixture=(owner,request)->{
            String html="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font:16px sans-serif;padding:16px;background:white;color:#171717}textarea{display:block;width:90%;height:140px}button{padding:12px}input[type=file]{display:none}</style><h2>离线附件流程验收</h2><p>测试页面，不调用 ChatGPT 服务</p><form><input type=file multiple accept='*/*'><div id=files></div><textarea id=prompt-textarea></textarea><button type=button data-testid=send-button disabled>发送</button></form><div id=messages></div><script>"+
                "window.fixtureOwner="+JSONObject.quote(owner)+";window.sentCount=0;window.receivedFiles=[];var input=document.querySelector('input'),text=document.querySelector('textarea'),send=document.querySelector('button'),box=document.querySelector('#files');function update(){send.disabled=!text.value||!window.receivedFiles.length;}text.addEventListener('input',update);input.onchange=async function(){box.innerHTML='<div role=progressbar>读取附件</div>';window.receivedFiles=[];for(var f of input.files){var bytes=await f.arrayBuffer(),hash=await crypto.subtle.digest('SHA-256',bytes);window.receivedFiles.push({name:f.name,size:f.size,sha:Array.from(new Uint8Array(hash)).map(x=>x.toString(16).padStart(2,'0')).join('')});}box.innerHTML='';for(var file of window.receivedFiles){var row=document.createElement('div');row.setAttribute('data-testid','attachment');row.setAttribute('data-attachment-name',file.name);row.textContent=file.name;var remove=document.createElement('button');remove.type='button';remove.setAttribute('data-testid','remove-file');remove.textContent='移除';row.appendChild(remove);box.appendChild(row);}update();};send.onclick=function(){if(send.disabled)return;window.sentCount++;var message=document.createElement('div');message.setAttribute('data-message-author-role','user');message.textContent=text.value;document.querySelector('#messages').appendChild(message);text.value='';update();history.pushState({},'', '/c/offline-fixture');};</script>";
            return new android.webkit.WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        };
        android.app.Instrumentation.ActivityMonitor monitor=InstrumentationRegistry.getInstrumentation().addMonitor(MainActivity.class.getName(),null,false);MainActivity main=null;
        Set<String> before=new HashSet<>();HandoffRuntime runtime=HandoffRuntime.get(context);for(JSONObject job:runtime.store.list(account))before.add(job.getString("id"));
        try(ActivityScenario<AssetWorkbenchActivity> scene=ActivityScenario.launch(screen(AssetWorkbenchActivity.class))){
            AtomicBoolean ready=new AtomicBoolean();until(()->{scene.onActivity(a->{ListView list=find(a.getWindow().getDecorView(),ListView.class);ready.set(list.getAdapter()!=null&&list.getAdapter().getCount()==2);});return ready.get();});
            tapNative("交给 GPT");tapNative("选择附件（0）");selectNativeFile("offline-report.html · "+android.text.format.Formatter.formatShortFileSize(context,originals.get(0).getLong("size")));selectNativeFile("offline-image.png · "+android.text.format.Formatter.formatShortFileSize(context,originals.get(1).getLong("size")));tapNative("确定");
            until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("选择附件（2）"))if("选择附件（2）".contentEquals(node.getText()==null?"":node.getText()))return true;return false;});
            chooseFormat("另一个测试账号 · fixture@example.test");chooseSpinner(1,"HTML 结构解读");screenshot("android-handoff-review.png");
            // A different account requires an explicit native checkbox. First try must stay here.
            tapNative("新建对话并发送");SystemClock.sleep(300);assertNull(monitor.getLastActivity());
            tapNative("我确认将所选文件发送到另一个账号");
            // Supply instructions through the real dialog edit field, retaining the chosen files.
            // Keep both the selected Skill and explicit instructions in the sent prompt.
            until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;return setInstruction(root);});
            tapNative("新建对话并发送");main=(MainActivity)InstrumentationRegistry.getInstrumentation().waitForMonitorWithTimeout(monitor,30000);assertNotNull("Target MainActivity was not opened",main);
            AtomicReference<JSONObject> result=new AtomicReference<>();until(45000,()->{for(JSONObject job:runtime.store.list(account))if(!before.contains(job.getString("id"))){result.set(job);return !HandoffStore.active(job.optString("status"));}return false;});JSONObject job=result.get();assertNotNull(job);assertEquals(job.toString(),"submitted",job.getString("status"));assertEquals(other,job.getString("targetAccountId"));assertEquals(account,job.getString("sourceAccountId"));
            MainActivity target=main;AtomicReference<String> page=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{WebView web=target.recoveryCurrentWeb();assertNotNull(web);assertFalse(web.getSettings().getAllowContentAccess());web.evaluateJavascript("JSON.stringify({owner:window.fixtureOwner,count:window.sentCount,files:window.receivedFiles,prompt:document.querySelector('[data-message-author-role=user]').textContent})",page::set);});until(()->page.get()!=null);JSONObject observed=new JSONObject(String.valueOf(new JSONTokener(page.get()).nextValue()));assertEquals(other,observed.getString("owner"));assertEquals(1,observed.getInt("count"));JSONArray uploaded=observed.getJSONArray("files");assertEquals(2,uploaded.length());
            for(int i=0;i<2;i++){assertEquals(originals.get(i).getLong("size"),uploaded.getJSONObject(i).getLong("size"));assertEquals(originals.get(i).getString("sha256"),uploaded.getJSONObject(i).getString("sha"));assertEquals(originals.get(i).getString("sha256"),ledger.get(originals.get(i).getString("id")).getString("sha256"));}
            assertTrue(uploaded.getJSONObject(0).getString("name").endsWith(".txt"));assertEquals(0,new AssetLedger(context.getFilesDir(),other).list().size());
            until(()->!new File(new File(new File(context.getFilesDir(),"handoff"),"staging"),job.getString("id")).exists());
            JSONObject payload=runtime.store.payload(job.getString("id"));assertEquals("skill-001",payload.getJSONObject("request").getString("skillId"));assertEquals(payload.getString("prompt"),observed.getString("prompt"));assertTrue(observed.getString("prompt").contains("HTML 结构解读"));JSONObject file=job.getJSONArray("files").getJSONObject(0);Uri revoked=new Uri.Builder().scheme("content").authority(context.getPackageName()+".handoff").appendPath(job.getString("id")).appendPath(payload.getString("uploadToken")).appendPath(file.getString("stageId")).build();
            try{context.getContentResolver().openInputStream(revoked);fail("Finished task still exposes file bytes");}catch(FileNotFoundException expected){}
            try{runtime.store.claim(job.getString("id"),payload.getString("launchToken"));fail("Completed task replayed");}catch(Exception expected){}
            screenshot("android-handoff-submitted.png");
        }finally{if(main!=null){MainActivity target=main;InstrumentationRegistry.getInstrumentation().runOnMainSync(target::finish);}MainActivity.handoffFixture=null;InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);}
    }
    private boolean setInstruction(android.view.accessibility.AccessibilityNodeInfo node){
        if("android.widget.EditText".equals(String.valueOf(node.getClassName()))&&String.valueOf(node.getHintText()).contains("说明希望")){android.os.Bundle value=new android.os.Bundle();value.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"请检查这些离线附件。不要访问外部服务。");return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,value);}
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null&&setInstruction(child))return true;}return false;
    }
    private View description(View root,String prefix){
        if(root.getContentDescription()!=null&&root.getContentDescription().toString().startsWith(prefix))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=description(((ViewGroup)root).getChildAt(i),prefix);if(found!=null)return found;}return null;
    }
    @Test public void publicProfileLanguageCapabilitiesAreMeasuredAcrossFramesAndWorkers()throws Exception{
        try(ActivityScenario<EnvironmentProbeActivity> scene=ActivityScenario.launch(new Intent(context,EnvironmentProbeActivity.class))){
            JSONObject evidence=new JSONObject().put("scope","Offline public API probe; no account data or real ChatGPT").put("androidApi",android.os.Build.VERSION.SDK_INT);
            android.content.pm.PackageInfo engine=androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context);evidence.put("webViewPackage",engine==null?"unknown":engine.packageName).put("webViewVersion",engine==null?"unknown":engine.versionName);
            JSONArray profiles=new JSONArray();
            for(int i=0;i<2;i++){
                final int slot=i;AtomicReference<JSONObject> result=new AtomicReference<>();
                until(25000,()->{scene.onActivity(a->a.pages[slot].evaluateJavascript("JSON.stringify(window.result||{})",raw->{try{result.set(new JSONObject(String.valueOf(new JSONTokener(raw).nextValue())));}catch(Exception ignored){}}));JSONObject r=result.get();return r!=null&&r.has("main")&&r.has("frame")&&(r.has("worker")||r.has("workerError"));});
                JSONObject profile=result.get();scene.onActivity(a->{try{profile.put("requestedContextLanguage",a.languages[slot]).put("requestedTimeZoneNotApplied",a.zones[slot]).put("customHeaderFeature",a.customHeaders).put("headerError",a.errors.getOrDefault(a.languages[slot],""));JSONObject requests=new JSONObject();for(Map.Entry<String,String> e:a.headers.entrySet())if(e.getKey().startsWith(a.languages[slot]+" "))requests.put(e.getKey().substring(a.languages[slot].length()+1),e.getValue());profile.put("acceptLanguageByRequest",requests);}catch(JSONException e){throw new RuntimeException(e);}});profiles.put(profile);
            }
            evidence.put("profiles",profiles);encodedEvidence("android-language-capabilities.json","application/json",new ByteArrayInputStream(evidence.toString(2).getBytes(StandardCharsets.UTF_8)));screenshot("android-language-api-probe.png");
            for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);assertTrue(p.toString(),p.getString("headerError").isEmpty());if(p.getBoolean("customHeaderFeature")){String desired=p.getString("requestedContextLanguage"),actual=p.getJSONObject("acceptLanguageByRequest").getString("https://language-probe.example.test/main");assertTrue("Custom header did not lead with requested language: "+p,actual.startsWith(desired));}}
        }
    }
    private void nativeMenu(ActivityScenario<MainActivity> scene){scene.onActivity(a->{View menu=description(a.getWindow().getDecorView(),"账号与操作菜单");assertNotNull(menu);assertTrue(menu.performClick());});}
    private void tapContaining(String text)throws Exception{until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){if(node.getText()!=null&&node.getText().toString().contains(text)){tapNode(node);return true;}}return false;});}
    private String pageValue(ActivityScenario<MainActivity> scene,String script)throws Exception{
        AtomicReference<String> value=new AtomicReference<>();scene.onActivity(a->{WebView web=a.recoveryCurrentWeb();assertNotNull(web);web.evaluateJavascript(script,value::set);});until(()->value.get()!=null);return String.valueOf(new JSONTokener(value.get()).nextValue());
    }
    private JSONObject recoveryPage(ActivityScenario<MainActivity> scene)throws Exception{return new JSONObject(pageValue(scene,"JSON.stringify((function(){var e=document.querySelector('#prompt-textarea'),s=document.querySelector('#reading');return {ready:!!e,draft:e?(e.tagName==='TEXTAREA'?e.value:e.innerText):'',scroll:s?s.scrollTop:0,owner:window.fixtureOwner,mark:window.fixtureMark||'',sent:window.sentCount||0};})())"));}
    private void recoveryWorkspace()throws Exception{WorkspaceState state=WorkspaceFiles.read(context.getFilesDir());state.activeAccount=account;state.find(account).current().url="https://chatgpt.com/c/recovery-fixture";new StateStore(context.getFilesDir()).save(state);}
    private void recoveryFixture(AtomicReference<String> prefill,AtomicBoolean editable,AtomicBoolean sent,AtomicInteger loads){
        MainActivity.handoffFixture=(owner,request)->{
            if(request.isForMainFrame())loads.incrementAndGet();StringBuilder body=new StringBuilder();for(int i=0;i<24;i++)body.append("<section data-message-id='fixture-message-").append(i).append("' style='height:160px;border-bottom:1px solid #ddd'>离线阅读素材 ").append(i).append("</section>");
            if(sent.get())body.append("<p data-message-author-role='user' id='already-sent'></p>");
            String html="<!doctype html><html><head><title>离线页面恢复验收</title><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:12px;font:16px sans-serif;color:#171717;background:white}#reading{height:56vh;overflow:auto;border:1px solid #ddd}#prompt-textarea{display:block;box-sizing:border-box;width:100%;height:90px;border:1px solid #aaa}p{margin:10px 0}</style></head><body><p>离线恢复测试，不调用 ChatGPT 服务</p><div id='reading'>"+body+"</div>"+(editable.get()?"<div id='prompt-textarea' contenteditable='true'></div>":"<textarea id='prompt-textarea'></textarea>")+"<button type='button' id='send'>离线发送计数</button><script>window.fixtureOwner="+JSONObject.quote(owner)+";window.sentCount=0;window.fixtureMark='';var e=document.querySelector('#prompt-textarea'),prefill="+JSONObject.quote(prefill.get())+";if(e.tagName==='TEXTAREA')e.value=prefill;else e.textContent=prefill;var previous=document.querySelector('#already-sent');if(previous)previous.textContent="+JSONObject.quote("网页已有不同草稿")+";document.querySelector('#send').onclick=function(){window.sentCount++;};</script></body></html>";
            return new android.webkit.WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        };
    }
    @Test public void draftsAndReadingRestoreAcrossRecreationAndPreserveConflicts()throws Exception{
        recoveryWorkspace();WorkspaceState.Account owner=WorkspaceFiles.read(context.getFilesDir()).find(account);String tab=owner.activeTab,url=owner.current().url;
        PageRecoveryStore records=new PageRecoveryStore(context.getFilesDir(),DevicePayloadKeys.recoveryCipher(context));AtomicReference<String> prefill=new AtomicReference<>("");AtomicBoolean editable=new AtomicBoolean(),sent=new AtomicBoolean();AtomicInteger loads=new AtomicInteger();recoveryFixture(prefill,editable,sent,loads);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            until(()->recoveryPage(scene).optBoolean("ready"));pageValue(scene,"(function(){var e=document.querySelector('#prompt-textarea');e.value='恢复前的草稿';e.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#reading').scrollTop=1400;return 'set';})()");
            until(()->{JSONObject r=records.load(account,owner.storageId,tab,url);return r!=null&&r.optString("draft").equals("恢复前的草稿")&&Math.abs(r.optDouble("scroll")-1400)<8;});
            scene.onActivity(a->BrowserMemory.get(a).clear());scene.recreate();until(()->{JSONObject p=recoveryPage(scene);return p.optString("draft").equals("恢复前的草稿")&&Math.abs(p.optDouble("scroll")-1400)<8;});assertEquals(0,recoveryPage(scene).getInt("sent"));screenshot("android-recovery-restored.png");
            prefill.set("网页已有不同草稿");scene.onActivity(a->BrowserMemory.get(a).clear());scene.recreate();until(()->recoveryPage(scene).optString("draft").equals("网页已有不同草稿"));
            until(()->{JSONObject r=records.load(account,owner.storageId,tab,url);return r!=null&&r.optString("draft").equals("网页已有不同草稿")&&r.optString("alternativeDraft").equals("恢复前的草稿");});
            screenshot("android-recovery-conflict.png");nativeMenu(scene);tapNative("本地恢复草稿");tapNative("关闭");
            editable.set(true);prefill.set("");scene.onActivity(a->BrowserMemory.get(a).clear());scene.recreate();until(()->recoveryPage(scene).optString("draft").equals("网页已有不同草稿"));assertEquals(0,recoveryPage(scene).getInt("sent"));screenshot("android-recovery-editable.png");
            sent.set(true);scene.onActivity(a->BrowserMemory.get(a).clear());scene.recreate();until(()->recoveryPage(scene).optBoolean("ready"));until(()->{JSONObject r=records.load(account,owner.storageId,tab,url);return r!=null&&r.optString("draft").isEmpty();});assertEquals("",recoveryPage(scene).getString("draft"));assertEquals(0,recoveryPage(scene).getInt("sent"));
        }finally{MainActivity.handoffFixture=null;}
    }
    @Test public void windowRecreationAndNativeTabCardsKeepVisitedPagesAndCustomTitles()throws Exception{
        recoveryWorkspace();AtomicInteger loads=new AtomicInteger();recoveryFixture(new AtomicReference<>(""),new AtomicBoolean(),new AtomicBoolean(),loads);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            until(()->recoveryPage(scene).optBoolean("ready"));AtomicReference<WebView> original=new AtomicReference<>();scene.onActivity(a->original.set(a.recoveryCurrentWeb()));String mark=WorkspaceState.id();pageValue(scene,"(function(){window.fixtureMark="+JSONObject.quote(mark)+";document.querySelector('#prompt-textarea').value='窗口重建保留的草稿';document.querySelector('#reading').scrollTop=1100;return 'set';})()");
            nativeMenu(scene);tapNative("切换账号");tapContaining("另一个测试账号");until(()->recoveryPage(scene).optString("owner").equals(other));
            AtomicReference<String> inactiveVisibility=new AtomicReference<>();until(()->{scene.onActivity(a->{assertEquals(View.VISIBLE,original.get().getVisibility());assertEquals(0f,original.get().getAlpha(),0f);assertFalse(original.get().isEnabled());assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,original.get().getImportantForAccessibility());original.get().evaluateJavascript("document.visibilityState",inactiveVisibility::set);});return "\"visible\"".equals(inactiveVisibility.get());});
            nativeMenu(scene);tapNative("切换账号");tapContaining("本地验收账号");until(()->recoveryPage(scene).optString("owner").equals(account));assertEquals(2,loads.get());
            scene.recreate();scene.onActivity(a->assertSame(original.get(),a.recoveryCurrentWeb()));assertEquals(mark,recoveryPage(scene).getString("mark"));assertEquals("窗口重建保留的草稿",recoveryPage(scene).getString("draft"));assertEquals(2,loads.get());
            scene.onActivity(a->{View tabs=description(a.getWindow().getDecorView(),"标签页：");assertNotNull(tabs);assertTrue(tabs.performClick());});
            scene.onActivity(a->{android.widget.GridView grid=find(a.getWindow().getDecorView(),android.widget.GridView.class);assertNotNull(grid);assertEquals(2,grid.getAdapter().getCount());assertEquals(context.getResources().getConfiguration().screenWidthDp>=600?3:2,grid.getNumColumns());find(a.getWindow().getDecorView(),EditText.class).setText("不应存在的标签");assertEquals(0,grid.getAdapter().getCount());find(a.getWindow().getDecorView(),EditText.class).setText("");});
            until(()->{AtomicBoolean found=new AtomicBoolean();scene.onActivity(a->{View menu=description(a.getWindow().getDecorView(),"管理标签页：");if(menu!=null){found.set(menu.performClick());}});return found.get();});tapNative("重命名");
            until(()->{android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();return root!=null&&setAccessibleText(root,"标签名称","窗口保活命名");});tapNative("保存");
            scene.onActivity(a->find(a.getWindow().getDecorView(),EditText.class).setText("窗口保活命名"));scene.recreate();
            scene.onActivity(a->{assertSame(original.get(),a.recoveryCurrentWeb());assertEquals("窗口保活命名",find(a.getWindow().getDecorView(),EditText.class).getText().toString());assertEquals(1,find(a.getWindow().getDecorView(),android.widget.GridView.class).getAdapter().getCount());});
            WorkspaceState saved=WorkspaceFiles.read(context.getFilesDir());assertTrue(saved.find(account).current().customTitle);assertEquals("窗口保活命名",saved.find(account).current().title);assertEquals(2,loads.get());scene.onActivity(a->find(a.getWindow().getDecorView(),EditText.class).setText(""));screenshot("android-tabs-native-overview.png");tapNative("完成");assertEquals(mark,recoveryPage(scene).getString("mark"));screenshot("android-browser-native-shell.png");
        }finally{MainActivity.handoffFixture=null;}
    }
    private boolean setAccessibleText(android.view.accessibility.AccessibilityNodeInfo node,String label,String text){
        if("android.widget.EditText".equals(String.valueOf(node.getClassName()))&&label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())){android.os.Bundle value=new android.os.Bundle();value.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,value);}
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null&&setAccessibleText(child,label,text))return true;}return false;
    }
    @Test public void switchingAccountsAndUndoCloseReuseTheLivePage()throws Exception{
        recoveryWorkspace();AtomicReference<String> prefill=new AtomicReference<>("");AtomicInteger loads=new AtomicInteger();recoveryFixture(prefill,new AtomicBoolean(),new AtomicBoolean(),loads);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            until(()->recoveryPage(scene).optBoolean("ready"));AtomicReference<WebView> originalWeb=new AtomicReference<>();scene.onActivity(a->originalWeb.set(a.recoveryCurrentWeb()));String marker=WorkspaceState.id();pageValue(scene,"(function(){window.fixtureMark="+JSONObject.quote(marker)+";var e=document.querySelector('#prompt-textarea');e.value='保留活页面的草稿';e.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#reading').scrollTop=1200;return 'set';})()");
            nativeMenu(scene);tapNative("切换账号");tapContaining("另一个测试账号");until(()->recoveryPage(scene).optString("owner").equals(other));
            nativeMenu(scene);tapNative("切换账号");tapContaining("本地验收账号");until(()->recoveryPage(scene).optString("owner").equals(account));scene.onActivity(a->assertSame(originalWeb.get(),a.recoveryCurrentWeb()));assertEquals(marker,recoveryPage(scene).getString("mark"));assertEquals("保留活页面的草稿",recoveryPage(scene).getString("draft"));assertEquals(2,loads.get());
            scene.onActivity(a->{View tabView=description(a.getWindow().getDecorView(),"标签页：");assertNotNull(tabView);assertTrue(tabView.performLongClick());});tapNative("关闭标签页");until(()->loads.get()==3&&recoveryPage(scene).optBoolean("ready"));
            nativeMenu(scene);tapNative("恢复关闭的标签页");until(()->recoveryPage(scene).optString("mark").equals(marker));scene.onActivity(a->assertSame(originalWeb.get(),a.recoveryCurrentWeb()));assertEquals(3,loads.get());assertEquals("保留活页面的草稿",recoveryPage(scene).getString("draft"));assertTrue(Math.abs(recoveryPage(scene).getDouble("scroll")-1200)<8);screenshot("android-recovery-undo-live-tab.png");
        }finally{MainActivity.handoffFixture=null;}
    }
}
