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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
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

/** This class references only APIs present in immutable Beta.6. */
@RunWith(AndroidJUnit4.class)
public final class EnvironmentUpgradeSeedTest {
    static final String PACKAGE="com.forcemind.chatgpt.profiles.networkbeta";
    static final String SIGNER="bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0";
    static final String EXPECTED="environment-upgrade-expected.json";
    static final String ORIGIN="https://environment-upgrade-fixture.invalid/";
    static final long OLD_CODE=3000033L, NEW_CODE=3000034L;
    static Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    static File file(String name){return new File(context().getFilesDir(),name);}
    static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    static String hash(byte[] bytes)throws Exception{
        StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
    static String hash(File file)throws Exception{return hash(Files.readAllBytes(file.toPath()));}
    static String signer()throws Exception{return hash(context().getPackageManager().getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray());}
    static int verifyIdentity(long version)throws Exception{
        assertEquals(PACKAGE,context().getPackageName());Bundle args=InstrumentationRegistry.getArguments();int api=Integer.parseInt(args.getString("expectedApi"));assertTrue(api==28||api==35);assertEquals(api,android.os.Build.VERSION.SDK_INT);
        assertEquals(version,context().getPackageManager().getPackageInfo(PACKAGE,0).getLongVersionCode());assertEquals(SIGNER,signer());
        if(args.containsKey("signer"))assertEquals(SIGNER,args.getString("signer"));
        String scope=args.getString("baselineScope");assertTrue("original-delivered-arm64".equals(scope)||"ci-x86-baseline".equals(scope));assertEquals(api==28,"ci-x86-baseline".equals(scope));return api;
    }
    static JSONObject expected()throws Exception{return new JSONObject(new String(Files.readAllBytes(file(EXPECTED).toPath()),StandardCharsets.UTF_8));}
    static void saveExpected(JSONObject expected)throws Exception{Files.write(file(EXPECTED).toPath(),expected.toString().getBytes(StandardCharsets.UTF_8));}
    static JSONObject seedNative()throws Exception{
        assertFalse("Refuse existing workspace",file("workspace.json").exists());assertFalse("Refuse fixture overwrite",file(EXPECTED).exists());
        WorkspaceState state=WorkspaceState.fresh();state.current().name="Owned legacy account";state.addTab(state.current());
        for(WorkspaceState.Tab tab:state.current().tabs)tab.url="https://chatgpt.com/c/owned-environment-upgrade-"+tab.id;
        new StateStore(context().getFilesDir()).save(state);WorkspaceState.Account account=state.current();
        JSONObject expected=new JSONObject().put("schema",1).put("fromVersionCode",OLD_CODE).put("toVersionCode",NEW_CODE).put("uid",android.os.Process.myUid()).put("signerSHA256",signer())
            .put("accountId",account.id).put("storageId",account.storageId).put("legacyAccount",account.json()).put("profile",WorkspaceState.profile(account.storageId)).put("marker","owned-"+WorkspaceState.id()).put("workspaceSHA256",hash(file("workspace.json")));
        JSONArray recoveries=new JSONArray();PageRecoveryStore recovery=new PageRecoveryStore(context().getFilesDir(),DevicePayloadKeys.recoveryCipher(context()));
        for(WorkspaceState.Tab tab:account.tabs){
            assertTrue(recovery.save(new JSONObject().put("schema",1).put("accountId",account.id).put("storageId",account.storageId).put("tabId",tab.id).put("url",tab.url).put("draft","owned environment recovery "+tab.id).put("scroll",321).put("ratio",0.25).put("capturedAt",123456789),recovery.generation(account.id,account.storageId)));
            String path="page-recovery/"+account.id+"/"+account.storageId+"/"+tab.id+".bin";recoveries.put(new JSONObject().put("tabId",tab.id).put("url",tab.url).put("path",path).put("sha256",hash(file(path))));
        }
        String payloadId=WorkspaceState.id();byte[] sealed=DevicePayloadKeys.cipher(context()).seal(payloadId,"owned environment upgrade payload");Files.write(file("environment-upgrade-payload.bin").toPath(),sealed);
        AssetLedger ledger=new AssetLedger(context().getFilesDir(),account.id);JSONObject asset=ledger.importCopy(new ByteArrayInputStream("owned environment upgrade asset".getBytes(StandardCharsets.UTF_8)),"preserved.txt","text/plain","import",null);
        NetworkSettingsStore.Data nodes=new NetworkSettingsStore.Data();nodes.nodes.add(NetworkNode.parse("vless://00000000-0000-4000-8000-000000000001@fixture.invalid:443?type=tcp&security=tls&sni=fixture.invalid","Owned disabled node"));nodes.selected=0;nodes.enabled=false;new NetworkSettingsStore(context()).write(nodes);
        assertTrue(context().getSharedPreferences("browser-preferences",0).edit().putString("environment-upgrade-marker","preserved").putBoolean("backgroundTasks",false).commit());
        expected.put("recoveries",recoveries).put("payloadId",payloadId).put("payloadSHA256",hash(sealed)).put("assetId",asset.getString("id")).put("assetSHA256",hash(ledger.verifiedFile(asset.getString("id")))).put("assetIndexSHA256",hash(file("assets/"+account.id+"/index.json")))
            .put("networkCipherSHA256",hash(new File(context().getNoBackupFilesDir(),"studio-network-v1.enc")))
            .put("preferencesSHA256",hash(new File(context().getApplicationInfo().dataDir,"shared_prefs/browser-preferences.xml")));
        saveExpected(expected);return expected;
    }
    static void verifyNative(JSONObject expected)throws Exception{
        assertEquals(1,expected.getInt("schema"));assertEquals(OLD_CODE,expected.getLong("fromVersionCode"));assertEquals(NEW_CODE,expected.getLong("toVersionCode"));assertEquals(expected.getInt("uid"),android.os.Process.myUid());assertEquals(expected.getString("signerSHA256"),signer());
        assertEquals(expected.getString("workspaceSHA256"),hash(file("workspace.json")));WorkspaceState state=new StateStore(context().getFilesDir()).load();assertEquals(1,state.accounts.size());WorkspaceState.Account account=state.current();
        assertEquals(expected.getString("accountId"),account.id);assertEquals(expected.getString("storageId"),account.storageId);assertEquals(2,account.tabs.size());assertTrue(account.closedTabs.isEmpty());assertTrue(state.pendingDeletes.isEmpty());
        byte[] sealed=Files.readAllBytes(file("environment-upgrade-payload.bin").toPath());assertEquals(expected.getString("payloadSHA256"),hash(sealed));assertEquals("owned environment upgrade payload",DevicePayloadKeys.cipher(context()).open(expected.getString("payloadId"),sealed));
        AssetLedger ledger=new AssetLedger(context().getFilesDir(),account.id);File asset=ledger.verifiedFile(expected.getString("assetId"));assertEquals(expected.getString("assetSHA256"),hash(asset));assertEquals("owned environment upgrade asset",new String(Files.readAllBytes(asset.toPath()),StandardCharsets.UTF_8));assertEquals(expected.getString("assetIndexSHA256"),hash(file("assets/"+account.id+"/index.json")));
        assertEquals(expected.getString("networkCipherSHA256"),hash(new File(context().getNoBackupFilesDir(),"studio-network-v1.enc")));NetworkSettingsStore.Data nodes=new NetworkSettingsStore(context()).read();assertEquals(1,nodes.nodes.size());assertFalse(nodes.enabled);assertEquals(0,nodes.selected);assertEquals("fixture.invalid",nodes.nodes.get(0).host);assertEquals("Owned disabled node",nodes.nodes.get(0).name);assertEquals("vless://00000000-0000-4000-8000-000000000001@fixture.invalid:443?type=tcp&security=tls&sni=fixture.invalid",nodes.nodes.get(0).link);
        assertEquals(expected.getString("preferencesSHA256"),hash(new File(context().getApplicationInfo().dataDir,"shared_prefs/browser-preferences.xml")));assertEquals("preserved",context().getSharedPreferences("browser-preferences",0).getString("environment-upgrade-marker",""));assertFalse(KeepaliveService.enabled(context()));
        PageRecoveryStore recovery=new PageRecoveryStore(context().getFilesDir(),DevicePayloadKeys.recoveryCipher(context()));JSONArray records=expected.getJSONArray("recoveries");assertEquals(2,records.length());
        for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);assertEquals(record.getString("tabId"),account.tabs.get(i).id);assertEquals(record.getString("url"),account.tabs.get(i).url);assertEquals(record.getString("sha256"),hash(file(record.getString("path"))));JSONObject restored=recovery.load(account.id,account.storageId,record.getString("tabId"),record.getString("url"));assertNotNull(restored);assertEquals("owned environment recovery "+record.getString("tabId"),restored.getString("draft"));assertEquals(321,restored.getInt("scroll"));assertEquals(0.25,restored.getDouble("ratio"),0);assertEquals(record.getString("sha256"),hash(file(record.getString("path"))));}
        assertEquals(expected.getString("workspaceSHA256"),hash(file("workspace.json")));
    }
    static void verifyNativePreservedSubset(JSONObject expected)throws Exception{
        assertEquals(1,expected.getInt("schema"));assertEquals(OLD_CODE,expected.getLong("fromVersionCode"));assertEquals(NEW_CODE,expected.getLong("toVersionCode"));assertEquals(expected.getInt("uid"),android.os.Process.myUid());assertEquals(expected.getString("signerSHA256"),signer());
        WorkspaceState state=new StateStore(context().getFilesDir()).load();WorkspaceState.Account account=state.find(expected.getString("accountId"));
        assertJsonContent(expected.getJSONObject("legacyAccount"),account.json());
        JSONObject workspace=new JSONObject(new String(Files.readAllBytes(file("workspace.json").toPath()),StandardCharsets.UTF_8));JSONArray saved=workspace.getJSONArray("accounts");JSONObject persisted=null;for(int i=0;i<saved.length();i++){JSONObject candidate=saved.getJSONObject(i);if(expected.getString("accountId").equals(candidate.getString("id")))persisted=candidate;}assertNotNull("Legacy account missing from persisted workspace",persisted);assertJsonContent(expected.getJSONObject("legacyAccount"),persisted);
        for(String field:new String[]{"engine","language","timeZone","environmentSlot"})assertFalse("Legacy account gained an environment default: "+field,persisted.has(field));
        assertEquals(expected.getString("accountId"),account.id);assertEquals(expected.getString("storageId"),account.storageId);assertEquals(2,account.tabs.size());assertTrue(account.closedTabs.isEmpty());
        byte[] sealed=Files.readAllBytes(file("environment-upgrade-payload.bin").toPath());assertEquals(expected.getString("payloadSHA256"),hash(sealed));assertEquals("owned environment upgrade payload",DevicePayloadKeys.cipher(context()).open(expected.getString("payloadId"),sealed));
        AssetLedger ledger=new AssetLedger(context().getFilesDir(),account.id);File asset=ledger.verifiedFile(expected.getString("assetId"));assertEquals(expected.getString("assetSHA256"),hash(asset));assertEquals("owned environment upgrade asset",new String(Files.readAllBytes(asset.toPath()),StandardCharsets.UTF_8));assertEquals(expected.getString("assetIndexSHA256"),hash(file("assets/"+account.id+"/index.json")));
        assertEquals(expected.getString("networkCipherSHA256"),hash(new File(context().getNoBackupFilesDir(),"studio-network-v1.enc")));NetworkSettingsStore.Data nodes=new NetworkSettingsStore(context()).read();assertEquals(1,nodes.nodes.size());assertFalse(nodes.enabled);assertEquals(0,nodes.selected);assertEquals("fixture.invalid",nodes.nodes.get(0).host);assertEquals("Owned disabled node",nodes.nodes.get(0).name);assertEquals("vless://00000000-0000-4000-8000-000000000001@fixture.invalid:443?type=tcp&security=tls&sni=fixture.invalid",nodes.nodes.get(0).link);
        assertEquals(expected.getString("preferencesSHA256"),hash(new File(context().getApplicationInfo().dataDir,"shared_prefs/browser-preferences.xml")));assertEquals("preserved",context().getSharedPreferences("browser-preferences",0).getString("environment-upgrade-marker",""));assertFalse(KeepaliveService.enabled(context()));
        PageRecoveryStore recovery=new PageRecoveryStore(context().getFilesDir(),DevicePayloadKeys.recoveryCipher(context()));JSONArray records=expected.getJSONArray("recoveries");assertEquals(2,records.length());
        for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);assertEquals(record.getString("tabId"),account.tabs.get(i).id);assertEquals(record.getString("url"),account.tabs.get(i).url);assertEquals(record.getString("sha256"),hash(file(record.getString("path"))));JSONObject restored=recovery.load(account.id,account.storageId,record.getString("tabId"),record.getString("url"));assertNotNull(restored);assertEquals("owned environment recovery "+record.getString("tabId"),restored.getString("draft"));assertEquals(321,restored.getInt("scroll"));assertEquals(0.25,restored.getDouble("ratio"),0);assertEquals(record.getString("sha256"),hash(file(record.getString("path"))));}
    }
    static void assertJsonContent(Object expected,Object actual)throws Exception{
        if(expected instanceof JSONObject){
            assertTrue("Expected JSON object",actual instanceof JSONObject);JSONObject left=(JSONObject)expected,right=(JSONObject)actual;HashSet<String> leftKeys=new HashSet<>(),rightKeys=new HashSet<>();java.util.Iterator<String> names=left.keys();while(names.hasNext())leftKeys.add(names.next());names=right.keys();while(names.hasNext())rightKeys.add(names.next());assertEquals("Account JSON keys changed",leftKeys,rightKeys);for(String key:leftKeys)assertJsonContent(left.get(key),right.get(key));
        }else if(expected instanceof JSONArray){
            assertTrue("Expected JSON array",actual instanceof JSONArray);JSONArray left=(JSONArray)expected,right=(JSONArray)actual;assertEquals("Account JSON array length changed",left.length(),right.length());for(int i=0;i<left.length();i++)assertJsonContent(left.get(i),right.get(i));
        }else if(expected instanceof Number){assertTrue(actual instanceof Number);assertEquals(((Number)expected).doubleValue(),((Number)actual).doubleValue(),0);}
        else assertEquals(expected,actual);
    }
    static String js(WebView view,String script)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();main(()->view.evaluateJavascript(script,value->{result.set(value);done.countDown();}));assertTrue("JavaScript timed out",done.await(15,TimeUnit.SECONDS));return result.get();
    }
    static void browser(JSONObject expected,boolean seed)throws Exception{
        CountDownLatch ready=new CountDownLatch(1);AtomicReference<WebView> holder=new AtomicReference<>();AtomicInteger http=new AtomicInteger();String profile=expected.getString("profile"),marker=expected.getString("marker");
        main(()->{
            assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));WebView view=new WebView(context());holder.set(view);
            if(!seed)assertTrue("Persisted profile missing",ProfileStore.getInstance().getAllProfileNames().contains(profile));WebViewCompat.setProfile(view,profile);view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);view.getSettings().setBlockNetworkLoads(true);
            view.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView web,WebResourceRequest request){String url=request.getUrl().toString();if(url.startsWith("data:"))return null;http.incrementAndGet();return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}@Override public void onPageFinished(WebView web,String url){ready.countDown();}});
            view.loadDataWithBaseURL(ORIGIN,"<!doctype html><title>Owned offline upgrade fixture</title><link rel='icon' href='data:,'><p id='owned'>offline-only</p>","text/html","UTF-8",null);
        });
        WebView view=holder.get();try{
            assertTrue("Inline document timed out",ready.await(25,TimeUnit.SECONDS));assertEquals("\"offline-only\"",js(view,"document.getElementById('owned').textContent"));assertEquals(ORIGIN.substring(0,ORIGIN.length()-1),new org.json.JSONTokener(js(view,"location.origin")).nextValue());
            if(seed){for(String cookie:new String[]{"owned_auth="+marker+"; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400","owned_account="+marker+"; Path=/; Secure; SameSite=Lax; Max-Age=86400"}){CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> accepted=new AtomicReference<>();main(()->WebViewCompat.getProfile(view).getCookieManager().setCookie(ORIGIN,cookie,ok->{accepted.set(ok);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,accepted.get());}assertEquals("true",js(view,"localStorage.setItem('owned-environment-account',"+JSONObject.quote(marker)+");true"));}
            AtomicReference<String> cookies=new AtomicReference<>();main(()->cookies.set(WebViewCompat.getProfile(view).getCookieManager().getCookie(ORIGIN)));assertNotNull(cookies.get());assertEquals(new HashSet<>(Arrays.asList("owned_auth="+marker,"owned_account="+marker)),new HashSet<>(Arrays.asList(cookies.get().split(";\\s*"))));assertEquals(JSONObject.quote("owned_account="+marker),js(view,"document.cookie"));assertEquals(JSONObject.quote(marker),js(view,"localStorage.getItem('owned-environment-account')"));
        }finally{main(()->{WebViewCompat.getProfile(view).getCookieManager().flush();view.destroy();});}
        assertEquals("HTTP must never be attempted",0,http.get());
    }
    static JSONObject registry(JSONObject expected)throws Exception{
        String profileName=expected.getString("profile");HashSet<String> expectedNames=new HashSet<>();expectedNames.add(profileName);
        if("verify-after-environment".equals(InstrumentationRegistry.getArguments().getString("phase"))){
            // The UI test deliberately created exactly one additional legacy space.
            // Authenticate that declared owner; unknown registrations still fail.
            JSONObject fixture=new JSONObject(new String(Files.readAllBytes(file("browser-chrome-profile.json").toPath()),StandardCharsets.UTF_8));assertEquals(1,fixture.getInt("schema"));
            WorkspaceState.Account added=new StateStore(context().getFilesDir()).load().find(fixture.getString("accountId"));assertNotEquals(expected.getString("accountId"),added.id);assertFalse(added.isEnvironment());assertEquals("网页测试",added.name);assertEquals(fixture.getString("storageId"),added.storageId);assertEquals(fixture.getString("profile"),WorkspaceState.profile(added.storageId));expectedNames.add(fixture.getString("profile"));assertEquals(2,expectedNames.size());
        }
        java.util.ArrayList<java.nio.file.Path> candidates=new java.util.ArrayList<>();
        registryCandidates(context().getDataDir(),4,candidates);
        for(java.nio.file.Path path:candidates){JSONObject prefs;try{prefs=new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));}catch(org.json.JSONException pendingWrite){continue;}JSONObject profile=prefs.optJSONObject("profile");if(profile==null||profile.optJSONArray("list")==null)continue;JSONArray list=profile.getJSONArray("list");HashSet<String> actualNames=new HashSet<>();JSONObject original=null;for(int i=0;i<list.length();i++){JSONObject entry=list.getJSONObject(i);assertTrue("Duplicate Chromium profile registration",actualNames.add(entry.getString("name")));if(profileName.equals(entry.getString("name")))original=entry;}if(!actualNames.equals(expectedNames)||original==null)continue;return new JSONObject().put("name",original.getString("name")).put("path",original.getString("path"));}return null;
    }
    /** Read-only UI barrier; the seed and final cold-reader paths above stay unchanged. */
    static JSONObject registry(JSONObject expected,boolean includeUi,JSONObject observation)throws Exception{
        assertTrue("UI registration barrier requires the declared UI profile",includeUi);
        String originalName=expected.getString("profile");JSONObject originalBinding=expected.getJSONObject("browserRegistry");
        assertEquals(originalName,originalBinding.getString("name"));
        JSONObject fixture=new JSONObject(new String(Files.readAllBytes(file("browser-chrome-profile.json").toPath()),StandardCharsets.UTF_8));assertEquals(1,fixture.getInt("schema"));
        WorkspaceState state=new StateStore(context().getFilesDir()).load();WorkspaceState.Account owner=state.find(fixture.getString("accountId"));
        assertNotEquals(expected.getString("accountId"),owner.id);assertFalse(owner.isEnvironment());assertEquals("网页测试",owner.name);assertEquals(fixture.getString("storageId"),owner.storageId);assertEquals(fixture.getString("profile"),WorkspaceState.profile(owner.storageId));
        HashSet<String> expectedNames=new HashSet<>();expectedNames.add(originalName);expectedNames.add(fixture.getString("profile"));assertEquals(2,expectedNames.size());
        java.util.ArrayList<String> sortedExpected=new java.util.ArrayList<>(expectedNames);java.util.Collections.sort(sortedExpected);observation.put("expectedProfileNames",new JSONArray(sortedExpected));
        java.util.ArrayList<java.nio.file.Path> candidates=new java.util.ArrayList<>();registryCandidates(context().getDataDir(),4,candidates);
        for(java.nio.file.Path path:candidates){
            JSONObject prefs;try{prefs=new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));}catch(org.json.JSONException pendingWrite){continue;}
            JSONObject profile=prefs.optJSONObject("profile");if(profile==null||profile.optJSONArray("list")==null)continue;
            JSONArray list=profile.getJSONArray("list");java.util.ArrayList<String> observedNames=new java.util.ArrayList<>();for(int i=0;i<list.length();i++)observedNames.add(list.getJSONObject(i).getString("name"));java.util.Collections.sort(observedNames);JSONArray actualNames=new JSONArray(observedNames);
            if(!observation.has("firstObservedProfileNames")){observation.put("firstObservedProfileNames",actualNames);observation.put("firstPrefStore",context().getDataDir().toPath().relativize(path).toString());}
            observation.put("lastObservedProfileNames",actualNames);
            HashSet<String> names=new HashSet<>(),paths=new HashSet<>();JSONObject old=null,added=null;
            for(int i=0;i<list.length();i++){
                JSONObject entry=list.getJSONObject(i);String name=entry.getString("name"),directory=entry.getString("path");
                assertTrue("Duplicate Chromium profile registration",names.add(name));assertFalse("Empty Chromium profile directory",directory.trim().isEmpty());assertTrue("Two profiles share one Chromium directory",paths.add(directory));
                if(originalName.equals(name))old=entry;if(fixture.getString("profile").equals(name))added=entry;
            }
            if(old!=null){assertEquals("Original profile name changed",originalBinding.getString("name"),old.getString("name"));assertEquals("Original profile directory changed",originalBinding.getString("path"),old.getString("path"));}
            if(!names.equals(expectedNames)||old==null||added==null)continue;
            return new JSONObject().put("original",new JSONObject().put("name",old.getString("name")).put("path",old.getString("path")))
                .put("ui",new JSONObject().put("name",added.getString("name")).put("path",added.getString("path")))
                .put("accountId",owner.id).put("storageId",owner.storageId).put("profileNames",actualNames);
        }
        return null;
    }
    static JSONObject uiRegistryBarrier(JSONObject expected)throws Exception{
        int api=android.os.Build.VERSION.SDK_INT;assertTrue("Owned API28/API35 UI path only",api==28||api==35);
        long start=android.os.SystemClock.elapsedRealtime(),deadline=start+20000;JSONObject observation=new JSONObject().put("schema",1).put("complete",false).put("api",api).put("readOnly",true);
        try{
            JSONObject registered=registry(expected,true,observation);
            while(registered==null&&android.os.SystemClock.elapsedRealtime()<deadline){long remaining=deadline-android.os.SystemClock.elapsedRealtime();if(remaining<=0)break;Thread.sleep(Math.min(100,remaining));if(android.os.SystemClock.elapsedRealtime()>=deadline)break;registered=registry(expected,true,observation);}
            assertNotNull("Exact original plus declared UI Chromium registrations must persist before process exit: "+observation,registered);
            observation.put("bindings",registered).put("complete",true);return observation;
        }finally{
            try{observation.put("registryBarrierMillis",android.os.SystemClock.elapsedRealtime()-start);if(!observation.has("firstObservedProfileNames"))observation.put("firstObservedProfileNames",JSONObject.NULL);
                Files.write(file("browser-chrome-registry-barrier.json").toPath(),observation.toString().getBytes(StandardCharsets.UTF_8));
                System.out.println("BROWSER_CHROME_REGISTRY_OBSERVATION "+observation);
                Bundle output=new Bundle();output.putString("browserChromeRegistryObservation",observation.toString());InstrumentationRegistry.getInstrumentation().addResults(output);
            }catch(Exception evidenceError){android.util.Log.e("BrowserChromeAcceptance","Cannot retain registry barrier observation",evidenceError);}
        }
    }
    private static void registryCandidates(File directory,int depth,java.util.List<java.nio.file.Path> output)throws Exception{
        File[] children=directory.listFiles();
        if(children==null){if(!directory.exists())return;throw new java.io.IOException("Cannot enumerate owned registration directory: "+directory);}
        for(File child:children){
            java.nio.file.Path path=child.toPath();
            if(child.getName().equals("pref_store")){
                // Only the target registration file has strict file-read semantics.
                // Session Storage/CURRENT and Chrome temp files are changing while
                // Chromium runs; their existence is not a registration invariant.
                java.nio.file.attribute.BasicFileAttributes attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if(attributes.isRegularFile()&&attributes.size()<=1024*1024)output.add(path);
            }else if(depth>1&&Files.isDirectory(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))registryCandidates(child,depth-1,output);
        }
    }
    static long registryBarrier(JSONObject expected,boolean seed)throws Exception{
        long start=android.os.SystemClock.elapsedRealtime();JSONObject persisted=registry(expected);while(seed&&persisted==null&&android.os.SystemClock.elapsedRealtime()-start<20000){Thread.sleep(100);persisted=registry(expected);}assertNotNull("Original Chromium registration must persist before process exit",persisted);
        if(seed){expected.put("browserRegistry",persisted);saveExpected(expected);}else{assertEquals(expected.getJSONObject("browserRegistry").getString("name"),persisted.getString("name"));assertEquals(expected.getJSONObject("browserRegistry").getString("path"),persisted.getString("path"));}return android.os.SystemClock.elapsedRealtime()-start;
    }
    static void noActivity(){main(()->{for(Stage stage:Stage.values())assertTrue("App Activity unexpectedly launched",ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).isEmpty());});}
    static void proof(JSONObject expected,String phase,int api,long version,long registryMillis,boolean engineChecked)throws Exception{
        JSONObject proof=new JSONObject().put("schema",1).put("complete",true).put("phase",phase).put("api",api).put("fromVersionCode",OLD_CODE).put("toVersionCode",NEW_CODE).put("versionCode",version).put("baselineScope",InstrumentationRegistry.getArguments().getString("baselineScope")).put("uid",android.os.Process.myUid()).put("signerSHA256",signer()).put("accounts",1).put("tabs",2).put("encryptedRecoveryRecords",2).put("encryptedPayloadPreserved",true).put("assetPreserved",true).put("assetSHA256",expected.getString("assetSHA256")).put("assetIndexSHA256",expected.getString("assetIndexSHA256")).put("payloadSHA256",expected.getString("payloadSHA256")).put("recoveryRecords",expected.getJSONArray("recoveries")).put("networkCipherSHA256",expected.getString("networkCipherSHA256")).put("preferencesSHA256",expected.getString("preferencesSHA256")).put("disabledNetworkConfigurationPreserved",true).put("preferencesPreserved",true).put("workspaceSHA256",expected.getString("workspaceSHA256")).put("expectedSHA256",hash(file(EXPECTED))).put("storageId",expected.getString("storageId")).put("persistedRegistryVerified",true).put("registryBarrierMillis",registryMillis).put("httpOnlySecureCookiePreserved",true).put("localStoragePreserved",true).put("legacyEngineChecked",engineChecked).put("inlineOfflineDocumentOnly",true).put("httpRequests",0).put("geckoOpened",false).put("appActivityLaunched",false).put("screenshots",false);
        boolean after="verify-after-environment".equals(phase);proof.put("afterEnvironmentUse",after).put("legacyAccountPreserved",engineChecked);
        if(after){WorkspaceState latest=new StateStore(context().getFilesDir()).load();int tabs=0;for(WorkspaceState.Account account:latest.accounts)tabs+=account.tabs.size();proof.put("baselineWorkspaceSHA256",expected.getString("workspaceSHA256")).put("workspaceSHA256",hash(file("workspace.json"))).put("accounts",latest.accounts.size()).put("tabs",tabs).put("verifiedLegacyAccounts",1).put("verifiedLegacyTabs",2);}
        Bundle result=new Bundle();result.putString("environmentUpgradeProof",proof.toString());InstrumentationRegistry.getInstrumentation().addResults(result);
    }
    @Test public void seedOrColdVerify()throws Exception{
        int api=verifyIdentity(OLD_CODE);String phase=InstrumentationRegistry.getArguments().getString("phase");assertTrue("seed".equals(phase)||"verify-old".equals(phase));boolean seed="seed".equals(phase);JSONObject expected=seed?seedNative():expected();verifyNative(expected);if(!seed)registryBarrier(expected,false);browser(expected,seed);long millis=registryBarrier(expected,seed);verifyNative(expected);noActivity();proof(expected,phase,api,OLD_CODE,millis,false);
    }
}
