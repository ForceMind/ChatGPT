package com.forcemind.chatgpt.profiles;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Verifies the persisted contract with platform APIs, without linking private app symbols. */
final class OptimizationUpgradeFiles {
    private static File regular(String path) throws Exception {
        File root = RegularEnvironmentUpgradeTest.context().getFilesDir().getCanonicalFile();
        File file = new File(root, path);
        assertTrue(file.getCanonicalPath().startsWith(root.getPath() + File.separator));
        assertTrue(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS));
        return file;
    }

    private static JSONObject json(String path) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(regular(path).toPath()), StandardCharsets.UTF_8));
    }

    private static JSONObject find(JSONArray rows, String id) throws Exception {
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (id.equals(row.getString("id"))) {
                assertNull("Duplicate persisted identifier", found);
                found = row;
            }
        }
        assertNotNull("Missing persisted identifier", found);
        return found;
    }

    private static String open(String suffix, String domain, String id, byte[] bytes) throws Exception {
        assertTrue(bytes.length >= 32);
        assertArrayEquals(new byte[]{'H', 'P', '0', '1'}, Arrays.copyOf(bytes, 4));
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        String alias = RegularEnvironmentUpgradeTest.context().getPackageName() + suffix;
        java.security.Key key = keys.getKey(alias, null);
        assertTrue("Existing key unavailable", key instanceof SecretKey);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 4, 12));
        cipher.updateAAD((domain + id).getBytes(StandardCharsets.UTF_8));
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(cipher.doFinal(bytes, 16, bytes.length - 16))).toString();
    }

    static void verify(JSONObject expected, boolean subset) throws Exception {
        assertEquals(1, expected.getInt("schema"));
        assertEquals(RegularEnvironmentUpgradeTest.OLD_CODE, expected.getLong("fromVersionCode"));
        assertEquals(RegularEnvironmentUpgradeTest.NEW_CODE, expected.getLong("toVersionCode"));
        assertEquals(expected.getInt("uid"), android.os.Process.myUid());
        assertEquals(expected.getString("signerSHA256"), RegularEnvironmentUpgradeTest.signer());
        String accountId = expected.getString("accountId");
        String storageId = expected.getString("storageId");
        JSONObject workspace = json("workspace.json");
        assertEquals(1, workspace.getInt("schema"));
        JSONArray accounts = workspace.getJSONArray("accounts");
        JSONObject account = find(accounts, accountId);
        RegularEnvironmentUpgradeTest.sameJson(expected.getJSONObject("legacyAccount"), account);
        assertEquals(storageId, account.getString("storageId"));
        assertEquals("profile-" + storageId, expected.getString("profile"));
        assertEquals(2, account.getJSONArray("tabs").length());
        for (String field : new String[]{"engine", "language", "timeZone", "environmentSlot"}) {
            assertFalse("Legacy account gained environment field " + field, account.has(field));
        }
        if (!subset) {
            assertEquals(1, accounts.length());
            assertEquals(0, workspace.getJSONArray("pendingDeletes").length());
            for (String field : new String[]{"environmentGroups", "pendingEnvironmentDeletes"}) {
                if (workspace.has(field)) assertEquals(0, workspace.getJSONArray(field).length());
            }
            assertEquals(expected.getString("workspaceSHA256"), RegularEnvironmentUpgradeTest.hash(regular("workspace.json")));
        }
        byte[] payload = Files.readAllBytes(regular("regular-upgrade-payload.bin").toPath());
        assertEquals(expected.getString("payloadSHA256"), RegularEnvironmentUpgradeTest.hash(payload));
        assertEquals("owned regular payload", open(".handoff-payloads-v1", "handoff-v1/", expected.getString("payloadId"), payload));

        String assetId = expected.getString("assetId");
        String assetPath = "assets/" + accountId + "/";
        JSONObject index = json(assetPath + "index.json");
        assertEquals(1, index.getInt("schema"));
        assertEquals(accountId, index.getString("accountId"));
        JSONObject asset = find(index.getJSONArray("records"), assetId);
        File assetFile = regular(assetPath + assetId + ".bin");
        assertEquals("ready", asset.getString("status"));
        assertFalse(asset.getBoolean("trashed"));
        assertEquals(asset.getLong("size"), assetFile.length());
        String assetHash = RegularEnvironmentUpgradeTest.hash(assetFile);
        assertEquals(expected.getString("assetSHA256"), assetHash);
        assertEquals(asset.getString("sha256"), assetHash);
        assertEquals(expected.getString("assetIndexSHA256"), RegularEnvironmentUpgradeTest.hash(regular(assetPath + "index.json")));

        android.content.Context context = RegularEnvironmentUpgradeTest.context();
        File preferences = new File(context.getApplicationInfo().dataDir, "shared_prefs/browser-preferences.xml");
        assertEquals(expected.getString("preferencesSHA256"), RegularEnvironmentUpgradeTest.hash(preferences));
        android.content.SharedPreferences settings = context.getSharedPreferences("browser-preferences", 0);
        assertEquals("preserved", settings.getString("regular-upgrade-marker", ""));
        assertFalse(settings.getBoolean("backgroundTasks", false));

        JSONArray records = expected.getJSONArray("recoveries");
        assertEquals(2, records.length());
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            String tab = record.getString("tabId");
            assertEquals("page-recovery/" + accountId + "/" + storageId + "/" + tab + ".bin", record.getString("path"));
            byte[] bytes = Files.readAllBytes(regular(record.getString("path")).toPath());
            assertEquals(record.getString("sha256"), RegularEnvironmentUpgradeTest.hash(bytes));
            JSONObject restored = new JSONObject(open(".page-recovery-v1", "page-recovery-v1/", tab, bytes));
            assertEquals(1, restored.getInt("schema"));
            assertEquals(accountId, restored.getString("accountId"));
            assertEquals(storageId, restored.getString("storageId"));
            assertEquals(tab, restored.getString("tabId"));
            assertEquals(record.getString("url"), restored.getString("url"));
            assertEquals("owned regular recovery " + tab, restored.getString("draft"));
            assertEquals(321, restored.getInt("scroll"));
            assertEquals(0.25, restored.getDouble("ratio"), 0);
        }
    }
}
