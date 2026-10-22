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
        long stop=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<stop){if(condition.value())return;SystemClock.sleep(150);}fail("Native fixture did not become ready");
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
                    AtomicBoolean ready=new AtomicBoolean();until(()->{scene.onActivity(a->{WebView web=a.getWindow().getDecorView().findViewWithTag("asset-html-preview");ready.set(web!=null&&web.getProgress()==100&&!web.getSettings().getJavaScriptEnabled()&&!web.getSettings().getAllowFileAccess()&&!web.getSettings().getAllowContentAccess());});return ready.get();});
                    screenshot("android-html-preview.png");
                    // Only this isolated test enables evaluation after the static page
                    // has loaded. The production settings above remain asserted off;
                    // CSP still forbids author scripts, including the fixture script.
                    AtomicReference<String> body=new AtomicReference<>();
                    AtomicBoolean authorScriptRan=new AtomicBoolean(true);
                    scene.onActivity(a->{WebView web=a.getWindow().getDecorView().findViewWithTag("asset-html-preview");web.getSettings().setJavaScriptEnabled(true);web.evaluateJavascript("JSON.stringify({body:document.body ? document.body.innerText : '',ran:window.shouldNotExecute===true})",value->{web.getSettings().setJavaScriptEnabled(false);try{JSONObject result=new JSONObject(String.valueOf(new JSONTokener(value).nextValue()));authorScriptRan.set(result.getBoolean("ran"));body.set(result.getString("body"));}catch(JSONException e){body.set("Invalid DOM result: "+value);}});});
                    until(()->body.get()!=null);
                    assertFalse("HTML author script executed",authorScriptRan.get());
                    assertTrue("Actual HTML body: "+body.get(),body.get().contains("本地 HTML 预览"));
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
}
