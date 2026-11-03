package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private, authenticated encryption; a failed read never replaces the existing file. */
public final class NetworkSettingsStore {
    private static final String ALIAS = "chatgpt-studio-network-v1";
    private final AtomicFile file;
    public static final class Data {
        public final List<NetworkNode> nodes = new ArrayList<>();
        public int selected = -1;
        public boolean enabled;
        Data copy() { Data out = new Data(); out.nodes.addAll(nodes); out.selected = selected; out.enabled = enabled; return out; }
    }
    public NetworkSettingsStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "studio-network-v1.enc")); }
    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            if (!create) throw new IllegalStateException("线路密钥不可用，已保留加密文件");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    public synchronized Data read() throws Exception {
        Data result = new Data();
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return result;
        byte[] encoded = file.readFully();
        if (encoded.length > 1024 * 1024 || encoded.length < 30 || encoded[0] != 1) throw new IllegalStateException("线路存储格式无效，文件已保留");
        byte[] nonce = new byte[12]; System.arraycopy(encoded, 1, nonce, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("chatgpt-studio-network-v1".getBytes(StandardCharsets.UTF_8));
        JSONObject json = new JSONObject(new String(cipher.doFinal(encoded, 13, encoded.length - 13), StandardCharsets.UTF_8));
        JSONArray nodes = json.getJSONArray("nodes");
        if (nodes.length() > 64) throw new IllegalStateException("线路数量超过上限");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.getJSONObject(i); result.nodes.add(NetworkNode.parse(n.getString("link"), n.getString("name")));
        }
        result.selected = json.optInt("selected", -1);
        result.enabled = json.getBoolean("enabled");
        if (result.selected < -1 || result.selected >= result.nodes.size() || (result.enabled && result.selected < 0)) throw new IllegalStateException("线路选择无效，原配置已保留");
        return result;
    }
    public synchronized void write(Data data) throws Exception {
        if (data.nodes.size() > 64 || data.selected < -1 || data.selected >= data.nodes.size() || (data.enabled && data.selected < 0)) throw new IllegalArgumentException("线路配置无效");
        JSONArray nodes = new JSONArray();
        for (NetworkNode n : data.nodes) nodes.put(new JSONObject().put("link", n.link).put("name", n.name));
        byte[] plain = new JSONObject().put("nodes", nodes).put("selected", data.selected).put("enabled", data.enabled).toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD("chatgpt-studio-network-v1".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] output = ByteBuffer.allocate(1 + cipher.getIV().length + encrypted.length).put((byte) 1).put(cipher.getIV()).put(encrypted).array();
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(output); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
}
