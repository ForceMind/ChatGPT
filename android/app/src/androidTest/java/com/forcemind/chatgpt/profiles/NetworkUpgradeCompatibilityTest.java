package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProfileStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Same class compiles against the public alpha.22 and new client. No Activity or network navigation. */
@RunWith(AndroidJUnit4.class)
public final class NetworkUpgradeCompatibilityTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private String hash(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private String signature()throws Exception{PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);return hash(p.signingInfo.getApkContentsSigners()[0].toByteArray());}
    private File file(String name){return new File(context.getFilesDir(),name);}
    private void seed()throws Exception{
        WorkspaceState state=WorkspaceState.fresh();WorkspaceState.Account first=state.current();first.name="Synthetic upgrade A";state.addTab(first);state.addAccount("Synthetic upgrade B","",WorkspaceState.COLORS[1]);state.addAccount("Synthetic upgrade C","",WorkspaceState.COLORS[2]);state.activeAccount=first.id;WorkspaceState.Tab closed=state.addTab(first);state.closeTab(first,closed.id);
        int sequence=0;for(WorkspaceState.Account a:state.accounts)for(WorkspaceState.Tab t:a.tabs){t.url="https://chatgpt.com/c/synthetic-upgrade-"+(sequence++);t.title="Synthetic persisted tab";t.customTitle=true;t.pinned=true;}
        new StateStore(context.getFilesDir()).save(state);
        JSONObject expected=new JSONObject().put("workspaceSHA256",hash(Files.readAllBytes(file("workspace.json").toPath()))).put("signingSHA256",signature()).put("accounts",new JSONArray());
        PageRecoveryStore recovery=new PageRecoveryStore(context.getFilesDir(),DevicePayloadKeys.recoveryCipher(context));
        for(int i=0;i<state.accounts.size();i++){
            WorkspaceState.Account a=state.accounts.get(i);String marker="synthetic-cookie-"+i;CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> accepted=new AtomicReference<>();
            main(()->ProfileStore.getInstance().getOrCreateProfile(WorkspaceState.profile(a.storageId)).getCookieManager().setCookie("https://chatgpt.com/","ci_upgrade_profile="+marker+"; Path=/; Secure; SameSite=Lax",ok->{accepted.set(ok);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(accepted.get());main(()->ProfileStore.getInstance().getProfile(WorkspaceState.profile(a.storageId)).getCookieManager().flush());
            JSONObject account=new JSONObject().put("id",a.id).put("storageId",a.storageId).put("profile",WorkspaceState.profile(a.storageId)).put("cookie",marker).put("tabs",new JSONArray());
            for(WorkspaceState.Tab t:a.tabs){String draft="synthetic encrypted draft "+t.id;JSONObject record=new JSONObject().put("schema",1).put("accountId",a.id).put("storageId",a.storageId).put("tabId",t.id).put("url",t.url).put("draft",draft).put("scroll",321).put("ratio",0.25).put("capturedAt",123456789);
                recovery.save(record,recovery.generation(a.id,a.storageId));File payload=file("page-recovery/"+a.id+"/"+a.storageId+"/"+t.id+".bin");account.getJSONArray("tabs").put(new JSONObject().put("id",t.id).put("url",t.url).put("draft",draft).put("ciphertextSHA256",hash(Files.readAllBytes(payload.toPath()))));}
            AssetLedger ledger=new AssetLedger(context.getFilesDir(),a.id);JSONObject asset=ledger.importCopy(new ByteArrayInputStream(("synthetic asset "+i).getBytes(StandardCharsets.UTF_8)),"preserved.txt","text/plain","import",null);
            account.put("assetId",asset.getString("id")).put("assetSHA256",hash(Files.readAllBytes(ledger.verifiedFile(asset.getString("id")).toPath())));
            expected.getJSONArray("accounts").put(account);
        }
        String token=WorkspaceState.id(),text="synthetic handoff identity";byte[] sealed=DevicePayloadKeys.cipher(context).seal(token,text);Files.write(file("upgrade-handoff.bin").toPath(),sealed);expected.put("handoffId",token).put("handoffText",text).put("handoffCiphertextSHA256",hash(sealed));
        context.getSharedPreferences("browser-preferences",0).edit().putBoolean("backgroundTasks",true).putString("synthetic-upgrade-marker","preserve").commit();
        Files.write(file("upgrade-expected.json").toPath(),expected.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void verify()throws Exception{
        JSONObject expected=new JSONObject(new String(Files.readAllBytes(file("upgrade-expected.json").toPath()),StandardCharsets.UTF_8));
        assertEquals(expected.getString("workspaceSHA256"),hash(Files.readAllBytes(file("workspace.json").toPath())));assertEquals(expected.getString("signingSHA256"),signature());
        WorkspaceState state=new StateStore(context.getFilesDir()).load();assertEquals(3,state.accounts.size());PageRecoveryStore recovery=new PageRecoveryStore(context.getFilesDir(),DevicePayloadKeys.recoveryCipher(context));
        for(int i=0;i<expected.getJSONArray("accounts").length();i++){
            JSONObject a=expected.getJSONArray("accounts").getJSONObject(i);WorkspaceState.Account actual=state.find(a.getString("id"));assertEquals(a.getString("storageId"),actual.storageId);assertEquals(a.getString("profile"),WorkspaceState.profile(actual.storageId));
            AtomicReference<String> cookie=new AtomicReference<>();main(()->{assertNotNull(ProfileStore.getInstance().getProfile(a.optString("profile")));cookie.set(ProfileStore.getInstance().getProfile(a.optString("profile")).getCookieManager().getCookie("https://chatgpt.com/"));});assertTrue(cookie.get().contains("ci_upgrade_profile="+a.getString("cookie")));
            for(int n=0;n<a.getJSONArray("tabs").length();n++){JSONObject tab=a.getJSONArray("tabs").getJSONObject(n);JSONObject read=recovery.load(actual.id,actual.storageId,tab.getString("id"),tab.getString("url"));assertNotNull(read);assertEquals(tab.getString("draft"),read.getString("draft"));assertEquals(321,read.getInt("scroll"));assertEquals(tab.getString("ciphertextSHA256"),hash(Files.readAllBytes(file("page-recovery/"+actual.id+"/"+actual.storageId+"/"+tab.getString("id")+".bin").toPath())));}
            AssetLedger ledger=new AssetLedger(context.getFilesDir(),actual.id);assertEquals(a.getString("assetSHA256"),hash(Files.readAllBytes(ledger.verifiedFile(a.getString("assetId")).toPath())));
        }
        byte[] sealed=Files.readAllBytes(file("upgrade-handoff.bin").toPath());assertEquals(expected.getString("handoffCiphertextSHA256"),hash(sealed));assertEquals(expected.getString("handoffText"),DevicePayloadKeys.cipher(context).open(expected.getString("handoffId"),sealed));
        assertTrue(KeepaliveService.enabled(context));assertEquals("preserve",context.getSharedPreferences("browser-preferences",0).getString("synthetic-upgrade-marker",""));
    }
    @Test public void syntheticUpgradeKeepsProfileAndEncryptedData()throws Exception{
        assertEquals("com.forcemind.chatgpt.profiles.debug",context.getPackageName());Bundle args=InstrumentationRegistry.getArguments();String phase=args.getString("phase");assertTrue("seed".equals(phase)||"verify".equals(phase));
        if("seed".equals(phase))seed();verify();
        JSONObject report=new JSONObject().put("schema",1).put("complete",true).put("phase",phase).put("api",android.os.Build.VERSION.SDK_INT).put("sourceSHA",args.getString("sourceSHA")).put("accounts",3).put("tabs",4).put("signingSHA256",signature()).put("sameTemporaryCISignature",true).put("productionSignatureVerified",false).put("browserNavigations",0).put("screenshots",false).put("personalData",false);
        Files.write(file("upgrade-result-"+phase+".json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8));System.out.println("UPGRADE_DATA_VERIFIED "+report);
    }
}
