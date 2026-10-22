package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.security.keystore.*;
import java.security.KeyStore;
import javax.crypto.*;

final class DevicePayloadKeys {
    static EncryptedPayloads cipher(Context context){String alias=context.getPackageName()+".handoff-payloads-v1";return new EncryptedPayloads(()->key(alias));}
    static EncryptedPayloads recoveryCipher(Context context){return new EncryptedPayloads(()->key(context.getPackageName()+".page-recovery-v1"),"page-recovery-v1/");}
    private static synchronized SecretKey key(String alias)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias)){java.security.Key value=store.getKey(alias,null);if(!(value instanceof SecretKey))throw new java.security.GeneralSecurityException("本机加密密钥不可用");return (SecretKey)value;}
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return generator.generateKey();
    }
}
