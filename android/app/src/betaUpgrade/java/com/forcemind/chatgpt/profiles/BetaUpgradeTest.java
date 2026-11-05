package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

/** Native persistence only: no Activity, WebView, real service, screenshots or personal data. */
@RunWith(AndroidJUnit4.class)
public final class BetaUpgradeTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File file(String name){return new File(context.getFilesDir(),name);}
    private String sha(byte[] data)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    @Test public void fixedSignerAndPrivateDataSurviveReplacement()throws Exception{
        assertEquals("com.forcemind.chatgpt.profiles.networkbeta",context.getPackageName());
        String phase=InstrumentationRegistry.getArguments().getString("phase");assertTrue("seed".equals(phase)||"verify".equals(phase));
        String signer=sha(context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray());
        assertEquals(InstrumentationRegistry.getArguments().getString("signer"),signer);
        long version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode();
        if("seed".equals(phase)){
            assertEquals(3000024L,version);
            WorkspaceState state=WorkspaceState.fresh();state.addTab(state.current());state.addAccount("Synthetic B","",WorkspaceState.COLORS[1]);state.addAccount("Synthetic C","",WorkspaceState.COLORS[2]);new StateStore(context.getFilesDir()).save(state);
            WorkspaceState.Account a=state.current();String id=WorkspaceState.id();byte[] sealed=DevicePayloadKeys.cipher(context).seal(id,"synthetic protected draft");Files.write(file("beta-draft.bin").toPath(),sealed);
            AssetLedger ledger=new AssetLedger(context.getFilesDir(),a.id);JSONObject asset=ledger.importCopy(new ByteArrayInputStream("synthetic asset bytes".getBytes(StandardCharsets.UTF_8)),"preserved.txt","text/plain","import",null);
            NetworkSettingsStore.Data nodes=new NetworkSettingsStore.Data();nodes.nodes.add(NetworkNode.parse("vless://00000000-0000-4000-8000-000000000001@fixture.invalid:443?type=tcp&security=tls&sni=fixture.invalid","Synthetic node"));nodes.selected=0;nodes.enabled=true;new NetworkSettingsStore(context).write(nodes);
            context.getSharedPreferences("browser-preferences",0).edit().putBoolean("backgroundTasks",true).putString("beta-marker","preserved").commit();
            JSONObject expected=new JSONObject().put("uid",android.os.Process.myUid()).put("signer",signer).put("stateSHA",sha(Files.readAllBytes(file("workspace.json").toPath()))).put("draftId",id).put("draftSHA",sha(sealed)).put("assetAccount",a.id).put("assetId",asset.getString("id")).put("assetSHA",sha(Files.readAllBytes(ledger.verifiedFile(asset.getString("id")).toPath()))).put("nodeCipherSHA",sha(Files.readAllBytes(new File(context.getNoBackupFilesDir(),"studio-network-v1.enc").toPath())));
            Files.write(file("beta-expected.json").toPath(),expected.toString().getBytes(StandardCharsets.UTF_8));
        }else assertEquals(3000025L,version);
        JSONObject expected=new JSONObject(new String(Files.readAllBytes(file("beta-expected.json").toPath()),StandardCharsets.UTF_8));
        assertEquals(expected.getInt("uid"),android.os.Process.myUid());assertEquals(expected.getString("signer"),signer);assertEquals(expected.getString("stateSHA"),sha(Files.readAllBytes(file("workspace.json").toPath())));WorkspaceState state=new StateStore(context.getFilesDir()).load();assertEquals(3,state.accounts.size());int tabs=0;for(WorkspaceState.Account a:state.accounts)tabs+=a.tabs.size();assertEquals(4,tabs);
        byte[] sealed=Files.readAllBytes(file("beta-draft.bin").toPath());assertEquals(expected.getString("draftSHA"),sha(sealed));assertEquals("synthetic protected draft",DevicePayloadKeys.cipher(context).open(expected.getString("draftId"),sealed));
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),expected.getString("assetAccount"));assertEquals(expected.getString("assetSHA"),sha(Files.readAllBytes(ledger.verifiedFile(expected.getString("assetId")).toPath())));
        assertEquals(expected.getString("nodeCipherSHA"),sha(Files.readAllBytes(new File(context.getNoBackupFilesDir(),"studio-network-v1.enc").toPath())));NetworkSettingsStore.Data nodes=new NetworkSettingsStore(context).read();assertEquals(1,nodes.nodes.size());assertEquals(0,nodes.selected);assertTrue(nodes.enabled);assertEquals("fixture.invalid",nodes.nodes.get(0).host);
        assertTrue(KeepaliveService.enabled(context));assertEquals("preserved",context.getSharedPreferences("browser-preferences",0).getString("beta-marker",""));
        System.out.println("BETA_UPGRADE_RESULT "+new JSONObject().put("complete",true).put("phase",phase).put("versionCode",version).put("uid",android.os.Process.myUid()).put("signerSHA256",signer).put("accounts",3).put("tabs",4).put("browserStarted",false).put("realNetworkRequests",0));
    }
}
