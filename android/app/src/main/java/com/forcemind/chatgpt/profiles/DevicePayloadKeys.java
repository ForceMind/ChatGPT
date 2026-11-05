package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.security.keystore.*;
import java.security.KeyStore;
import javax.crypto.*;

final class DevicePayloadKeys {
    static EncryptedPayloads cipher(Context context){Context app=context.getApplicationContext();String alias=app.getPackageName()+".handoff-payloads-v1";return new EncryptedPayloads(()->key(app,alias));}
    static EncryptedPayloads recoveryCipher(Context context){Context app=context.getApplicationContext();return new EncryptedPayloads(()->key(app,app.getPackageName()+".page-recovery-v1"),"page-recovery-v1/");}
    private static synchronized SecretKey key(Context context,String alias)throws Exception{
        // Multiple environment processes may reach the first key creation together.
        // Keep the existing alias/key and serialize only contains/get/create, never rotate it.
        try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(new java.io.File(context.getFilesDir(),"payload-keys.lock"),"rw");
            java.nio.channels.FileLock held=file.getChannel().lock()){
            return keyLocked(alias);
        }
    }
    private static SecretKey keyLocked(String alias)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias)){java.security.Key value=store.getKey(alias,null);if(!(value instanceof SecretKey))throw new java.security.GeneralSecurityException("本机加密密钥不可用");return (SecretKey)value;}
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return generator.generateKey();
    }
}
