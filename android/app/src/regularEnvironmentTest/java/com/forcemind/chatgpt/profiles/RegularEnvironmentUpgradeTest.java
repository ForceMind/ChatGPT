package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Instrumentation code only; all target business APIs below already exist in public alpha.22. */
@RunWith(AndroidJUnit4.class)
public final class RegularEnvironmentUpgradeTest {
    static final String PACKAGE="com.forcemind.chatgpt.profiles";
    static final long OLD_CODE=3000022L, NEW_CODE=3000035L;
    static final String EXPECTED="regular-environment-upgrade-expected.json";
    static final String ORIGIN="https://regular-upgrade-fixture.invalid/";
    static Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    static File file(String name){return new File(context().getFilesDir(),name);}
    static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    static String hash(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    static String hash(File source)throws Exception{return hash(Files.readAllBytes(source.toPath()));}
    static String signer()throws Exception{return hash(context().getPackageManager().getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray());}
    static Bundle args(){return InstrumentationRegistry.getArguments();}
    static int identity(long code)throws Exception{
        assertEquals(PACKAGE,context().getPackageName());int api=Integer.parseInt(args().getString("expectedApi"));assertTrue(api==28||api==35);assertEquals(api,android.os.Build.VERSION.SDK_INT);
        assertEquals(code,context().getPackageManager().getPackageInfo(PACKAGE,0).getLongVersionCode());String expected=args().getString("signer");assertNotNull(expected);assertTrue(expected.matches("[a-f0-9]{64}"));
        assertNotEquals("8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19",expected);
        assertNotEquals("bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0",expected);
        assertEquals(expected,signer());assertEquals("original-public-alpha22-signature-only-resigned",args().getString("baselineScope"));return api;
    }
    static JSONObject expected()throws Exception{return new JSONObject(new String(Files.readAllBytes(file(EXPECTED).toPath()),StandardCharsets.UTF_8));}
    static void save(JSONObject expected)throws Exception{Files.write(file(EXPECTED).toPath(),expected.toString().getBytes(StandardCharsets.UTF_8));}
    static JSONObject seed()throws Exception{
        assertFalse("Fresh app only",file("workspace.json").exists());assertFalse(file(EXPECTED).exists());WorkspaceState state=WorkspaceState.fresh();state.current().name="Owned regular legacy account";state.addTab(state.current());
        WorkspaceState.Account account=state.current();for(WorkspaceState.Tab tab:account.tabs){tab.url="https://chatgpt.com/c/owned-regular-upgrade-"+tab.id;tab.title="Owned legacy tab";tab.customTitle=true;}
        new StateStore(context().getFilesDir()).save(state);
        JSONObject expected=new JSONObject().put("schema",1).put("fromVersionCode",OLD_CODE).put("toVersionCode",NEW_CODE).put("uid",android.os.Process.myUid()).put("signerSHA256",signer()).put("accountId",account.id).put("storageId",account.storageId).put("legacyAccount",account.json()).put("profile",WorkspaceState.profile(account.storageId)).put("marker","owned-"+WorkspaceState.id()).put("workspaceSHA256",hash(file("workspace.json")));
        JSONArray records=new JSONArray();PageRecoveryStore recovery=new PageRecoveryStore(context().getFilesDir(),DevicePayloadKeys.recoveryCipher(context()));
        for(WorkspaceState.Tab tab:account.tabs){assertTrue(recovery.save(new JSONObject().put("schema",1).put("accountId",account.id).put("storageId",account.storageId).put("tabId",tab.id).put("url",tab.url).put("draft","owned regular recovery "+tab.id).put("scroll",321).put("ratio",0.25).put("capturedAt",123456789),recovery.generation(account.id,account.storageId)));String path="page-recovery/"+account.id+"/"+account.storageId+"/"+tab.id+".bin";records.put(new JSONObject().put("tabId",tab.id).put("url",tab.url).put("path",path).put("sha256",hash(file(path))));}
        String token=WorkspaceState.id();byte[] sealed=DevicePayloadKeys.cipher(context()).seal(token,"owned regular payload");Files.write(file("regular-upgrade-payload.bin").toPath(),sealed);
        AssetLedger ledger=new AssetLedger(context().getFilesDir(),account.id);JSONObject asset=ledger.importCopy(new ByteArrayInputStream("owned regular asset".getBytes(StandardCharsets.UTF_8)),"preserved.txt","text/plain","import",null);
        assertTrue(context().getSharedPreferences("browser-preferences",0).edit().putString("regular-upgrade-marker","preserved").putBoolean("backgroundTasks",false).commit());
        expected.put("recoveries",records).put("payloadId",token).put("payloadSHA256",hash(sealed)).put("assetId",asset.getString("id")).put("assetSHA256",hash(ledger.verifiedFile(asset.getString("id")))).put("assetIndexSHA256",hash(file("assets/"+account.id+"/index.json"))).put("preferencesSHA256",hash(new File(context().getApplicationInfo().dataDir,"shared_prefs/browser-preferences.xml")));save(expected);return expected;
    }
    static void sameJson(Object left,Object right)throws Exception{
        if(left instanceof JSONObject){assertTrue(right instanceof JSONObject);JSONObject a=(JSONObject)left,b=(JSONObject)right;HashSet<String> x=new HashSet<>(),y=new HashSet<>();java.util.Iterator<String> it=a.keys();while(it.hasNext())x.add(it.next());it=b.keys();while(it.hasNext())y.add(it.next());assertEquals(x,y);for(String key:x)sameJson(a.get(key),b.get(key));}
        else if(left instanceof JSONArray){assertTrue(right instanceof JSONArray);JSONArray a=(JSONArray)left,b=(JSONArray)right;assertEquals(a.length(),b.length());for(int i=0;i<a.length();i++)sameJson(a.get(i),b.get(i));}
        else if(left instanceof Number){assertTrue(right instanceof Number);assertEquals(((Number)left).doubleValue(),((Number)right).doubleValue(),0);}else assertEquals(left,right);
    }
    static void verifyNative(JSONObject expected,boolean subset)throws Exception{
        assertEquals(1,expected.getInt("schema"));assertEquals(OLD_CODE,expected.getLong("fromVersionCode"));assertEquals(NEW_CODE,expected.getLong("toVersionCode"));assertEquals(expected.getInt("uid"),android.os.Process.myUid());assertEquals(expected.getString("signerSHA256"),signer());
        WorkspaceState state=new StateStore(context().getFilesDir()).load();WorkspaceState.Account account=state.find(expected.getString("accountId"));assertEquals(expected.getString("storageId"),account.storageId);assertEquals(expected.getString("profile"),WorkspaceState.profile(account.storageId));sameJson(expected.getJSONObject("legacyAccount"),account.json());assertEquals(2,account.tabs.size());
        JSONObject saved=new JSONObject(new String(Files.readAllBytes(file("workspace.json").toPath()),StandardCharsets.UTF_8)),legacy=null;JSONArray accounts=saved.getJSONArray("accounts");for(int i=0;i<accounts.length();i++)if(account.id.equals(accounts.getJSONObject(i).getString("id")))legacy=accounts.getJSONObject(i);assertNotNull(legacy);sameJson(expected.getJSONObject("legacyAccount"),legacy);
        if(!subset){assertEquals(1,state.accounts.size());assertTrue(state.pendingDeletes.isEmpty());assertEquals(expected.getString("workspaceSHA256"),hash(file("workspace.json")));}
        byte[] sealed=Files.readAllBytes(file("regular-upgrade-payload.bin").toPath());assertEquals(expected.getString("payloadSHA256"),hash(sealed));assertEquals("owned regular payload",DevicePayloadKeys.cipher(context()).open(expected.getString("payloadId"),sealed));
        AssetLedger ledger=new AssetLedger(context().getFilesDir(),account.id);assertEquals(expected.getString("assetSHA256"),hash(ledger.verifiedFile(expected.getString("assetId"))));assertEquals(expected.getString("assetIndexSHA256"),hash(file("assets/"+account.id+"/index.json")));
        assertEquals(expected.getString("preferencesSHA256"),hash(new File(context().getApplicationInfo().dataDir,"shared_prefs/browser-preferences.xml")));assertEquals("preserved",context().getSharedPreferences("browser-preferences",0).getString("regular-upgrade-marker",""));assertFalse(KeepaliveService.enabled(context()));
        PageRecoveryStore recovery=new PageRecoveryStore(context().getFilesDir(),DevicePayloadKeys.recoveryCipher(context()));JSONArray records=expected.getJSONArray("recoveries");assertEquals(2,records.length());for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);assertEquals(record.getString("sha256"),hash(file(record.getString("path"))));JSONObject restored=recovery.load(account.id,account.storageId,record.getString("tabId"),record.getString("url"));assertNotNull(restored);assertEquals("owned regular recovery "+record.getString("tabId"),restored.getString("draft"));assertEquals(321,restored.getInt("scroll"));assertEquals(0.25,restored.getDouble("ratio"),0);}
    }
    static String js(WebView view,String code)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();main(()->view.evaluateJavascript(code,result->{value.set(result);done.countDown();}));assertTrue(done.await(15,TimeUnit.SECONDS));return value.get();}
    static void browser(JSONObject expected,boolean seed)throws Exception{
        String profile=expected.getString("profile"),marker=expected.getString("marker");CountDownLatch ready=new CountDownLatch(1);AtomicReference<WebView> holder=new AtomicReference<>();AtomicInteger requests=new AtomicInteger();
        main(()->{assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));WebView view=new WebView(context());holder.set(view);WebViewCompat.setProfile(view,profile);view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);view.getSettings().setBlockNetworkLoads(true);view.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView web,WebResourceRequest request){if(request.getUrl().toString().startsWith("data:"))return null;requests.incrementAndGet();return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}@Override public void onPageFinished(WebView web,String url){ready.countDown();}});view.loadDataWithBaseURL(ORIGIN,"<!doctype html><title>Owned offline regular upgrade fixture</title><link rel='icon' href='data:,'><p id='owned'>offline-only</p>","text/html","UTF-8",null);});
        WebView view=holder.get();try{assertTrue(ready.await(25,TimeUnit.SECONDS));assertEquals("\"offline-only\"",js(view,"document.getElementById('owned').textContent"));assertEquals(JSONObject.quote(ORIGIN.substring(0,ORIGIN.length()-1)),js(view,"location.origin"));
            if(seed){for(String cookie:new String[]{"owned_auth="+marker+"; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400","owned_account="+marker+"; Path=/; Secure; SameSite=Lax; Max-Age=86400"}){CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> ok=new AtomicReference<>();main(()->WebViewCompat.getProfile(view).getCookieManager().setCookie(ORIGIN,cookie,result->{ok.set(result);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,ok.get());}assertEquals("true",js(view,"localStorage.setItem('owned-regular-storage',"+JSONObject.quote(marker)+");true"));}
            AtomicReference<String> cookies=new AtomicReference<>();main(()->cookies.set(WebViewCompat.getProfile(view).getCookieManager().getCookie(ORIGIN)));assertNotNull(cookies.get());assertEquals(new HashSet<>(Arrays.asList("owned_auth="+marker,"owned_account="+marker)),new HashSet<>(Arrays.asList(cookies.get().split(";\\s*"))));assertEquals(JSONObject.quote("owned_account="+marker),js(view,"document.cookie"));assertEquals(JSONObject.quote(marker),js(view,"localStorage.getItem('owned-regular-storage')"));
            String operation=seed?"const w=db.transaction('owned','readwrite');w.objectStore('owned').put("+JSONObject.quote(marker)+",'marker');w.oncomplete=()=>{db.close();window.ownedIdb='stored';};":"const r=db.transaction('owned').objectStore('owned').get('marker');r.onsuccess=()=>{window.ownedIdb=r.result;db.close();};";
            assertEquals("true",js(view,"window.ownedIdb=null;window.ownedIdbError=null;(()=>{const q=indexedDB.open('owned-regular-upgrade',1);q.onupgradeneeded=()=>q.result.createObjectStore('owned');q.onerror=()=>window.ownedIdbError='open';q.onsuccess=()=>{const db=q.result;"+operation+"};})();true"));
            long deadline=android.os.SystemClock.elapsedRealtime()+15000;String expectedValue=JSONObject.quote(seed?"stored":marker),value;do{value=js(view,"window.ownedIdb");if(expectedValue.equals(value))break;assertEquals("null",js(view,"window.ownedIdbError"));Thread.sleep(100);}while(android.os.SystemClock.elapsedRealtime()<deadline);assertEquals(expectedValue,value);
        }finally{main(()->{WebViewCompat.getProfile(view).getCookieManager().flush();if(view.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)view.getParent()).removeView(view);view.destroy();});}assertEquals("Inline fixture attempted a request",0,requests.get());
    }
    static JSONObject registry(JSONObject expected)throws Exception{
        java.util.ArrayList<File> candidates=new java.util.ArrayList<>();collect(context().getDataDir(),4,candidates);JSONObject binding=null;
        for(File candidate:candidates){JSONObject prefs;try{prefs=new JSONObject(new String(Files.readAllBytes(candidate.toPath()),StandardCharsets.UTF_8));}catch(org.json.JSONException pending){continue;}JSONObject p=prefs.optJSONObject("profile");if(p==null||p.optJSONArray("list")==null)continue;JSONArray entries=p.getJSONArray("list");HashSet<String> names=new HashSet<>(),paths=new HashSet<>();for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);assertTrue(names.add(entry.getString("name")));assertTrue(paths.add(entry.getString("path")));if(expected.getString("profile").equals(entry.getString("name")))binding=new JSONObject().put("name",entry.getString("name")).put("path",entry.getString("path"));}}return binding;
    }
    static void collect(File root,int depth,java.util.List<File> out)throws Exception{File[] files=root.listFiles();if(files==null){if(!root.exists())return;throw new java.io.IOException("Cannot inspect Chromium registry: "+root);}for(File item:files){if(item.getName().equals("pref_store")&&Files.isRegularFile(item.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&item.length()<=1024*1024)out.add(item);else if(depth>1&&Files.isDirectory(item.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))collect(item,depth-1,out);}}
    static void registryBarrier(JSONObject expected,boolean seed)throws Exception{long deadline=android.os.SystemClock.elapsedRealtime()+20000;JSONObject binding;do{binding=registry(expected);if(binding!=null)break;Thread.sleep(100);}while(seed&&android.os.SystemClock.elapsedRealtime()<deadline);assertNotNull("Original Chromium registration must already persist",binding);if(seed){expected.put("browserRegistry",binding);save(expected);}else sameJson(expected.getJSONObject("browserRegistry"),binding);}
    static void execute(String phase,long version,boolean seed,boolean subset)throws Exception{
        int api=identity(version);JSONObject expected=seed?seed():expected();verifyNative(expected,subset);String expectedHash=hash(file(EXPECTED));
        // Read persisted registration BEFORE the fixture can recreate its profile.
        if(!seed){registryBarrier(expected,false);main(()->assertTrue(ProfileStore.getInstance().getAllProfileNames().contains(expected.optString("profile"))));}
        browser(expected,seed);registryBarrier(expected,seed);
        verifyNative(expected,subset);if(!seed)assertEquals(expectedHash,hash(file(EXPECTED)));
        JSONObject proof=new JSONObject().put("schema",1).put("complete",true).put("phase",phase).put("api",api).put("fromVersionCode",OLD_CODE).put("toVersionCode",NEW_CODE).put("versionCode",version).put("baselineScope",args().getString("baselineScope")).put("uid",android.os.Process.myUid()).put("signerSHA256",signer()).put("storageId",expected.getString("storageId")).put("expectedSHA256",hash(file(EXPECTED))).put("baselineWorkspaceSHA256",expected.getString("workspaceSHA256")).put("workspaceSHA256",hash(file("workspace.json"))).put("recoveryRecords",expected.getJSONArray("recoveries"));
        for(String key:new String[]{"payloadSHA256","assetSHA256","assetIndexSHA256","preferencesSHA256"})proof.put(key,expected.getString(key));
        for(String key:new String[]{"encryptedPayloadPreserved","assetPreserved","preferencesPreserved","persistedRegistryVerified","httpOnlySecureCookiePreserved","localStoragePreserved","indexedDbPreserved","inlineOfflineDocumentOnly","sameTemporaryCISignature"})proof.put(key,true);
        proof.put("appActivityLaunched",false).put("productionSignatureVerified",false).put("encryptedRecoveryRecords",2).put("verifiedLegacyAccounts",1).put("verifiedLegacyTabs",2).put("fixtureHttpRequests",0).put("hostFixture","target application context; inline owned WebView; no Activity launch").put("screenshots",false).put("geckoOpened",false).put("legacyAccountPreserved",version==NEW_CODE).put("afterEnvironmentUse",subset);
        Bundle output=new Bundle();output.putString("regularEnvironmentUpgradeProof",proof.toString());InstrumentationRegistry.getInstrumentation().addResults(output);
    }
    @Test public void seedOrColdReadOriginalPublicApp()throws Exception{String phase=args().getString("phase");assertTrue("seed".equals(phase)||"verify-old".equals(phase));execute(phase,OLD_CODE,"seed".equals(phase),false);}
}
