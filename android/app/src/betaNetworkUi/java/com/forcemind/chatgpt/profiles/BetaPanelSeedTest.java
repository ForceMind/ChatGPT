package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.Bundle;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

/** Runs against the immutable delivered Beta.3, using only APIs already in that APK. */
@RunWith(AndroidJUnit4.class)
public final class BetaPanelSeedTest {
    static String hash(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    @Test public void seedOwnedExistingData() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.forcemind.chatgpt.profiles.networkbeta", context.getPackageName());
        assertEquals(3000027L, context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode());
        assertFalse(new File(context.getFilesDir(), "workspace.json").exists());
        String signer = hash(context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray());
        assertEquals(InstrumentationRegistry.getArguments().getString("signer"), signer);
        WorkspaceState state = WorkspaceState.fresh(); new StateStore(context.getFilesDir()).save(state);
        NetworkSettingsStore.Data nodes = new NetworkSettingsStore.Data();
        nodes.nodes.add(NetworkNode.parse("vless://00000000-0000-4000-8000-000000000001@fixture.invalid:443?type=tcp&security=tls&sni=fixture.invalid", "原线路"));
        nodes.selected = 0; nodes.enabled = false; new NetworkSettingsStore(context).write(nodes);
        String id = WorkspaceState.id(); byte[] sealed = DevicePayloadKeys.cipher(context).seal(id, "owned panel draft");
        Files.write(new File(context.getFilesDir(), "panel-draft.bin").toPath(), sealed);
        JSONObject proof = new JSONObject().put("uid", android.os.Process.myUid()).put("signer", signer).put("draftId", id)
            .put("workspaceSHA256", hash(Files.readAllBytes(new File(context.getFilesDir(), "workspace.json").toPath())))
            .put("nodeCipherSHA256", hash(Files.readAllBytes(new File(context.getNoBackupFilesDir(), "studio-network-v1.enc").toPath())))
            .put("draftSHA256", hash(sealed));
        Files.write(new File(context.getFilesDir(), "panel-existing.json").toPath(), proof.toString().getBytes(StandardCharsets.UTF_8));
        Bundle result = new Bundle(); result.putString("panelSeedProof", proof.put("complete", true).toString());
        InstrumentationRegistry.getInstrumentation().addResults(result);
    }
}
