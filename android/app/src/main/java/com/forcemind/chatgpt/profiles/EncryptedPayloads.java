package com.forcemind.chatgpt.profiles;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.GeneralSecurityException;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Authenticated local payloads; job identity is bound as additional data. */
final class EncryptedPayloads {
    interface Keys {SecretKey get()throws Exception;}
    private final Keys keys;
    private final String domain;
    private final int maxPlain;
    EncryptedPayloads(Keys keys){this(keys,"handoff-v1/");}
    EncryptedPayloads(Keys keys,String domain){if(!domain.matches("[a-z-]+-v[0-9]+/"))throw new IllegalArgumentException("Invalid encryption domain");this.keys=keys;this.domain=domain;maxPlain=domain.equals("page-recovery-v1/")?1024*1024:512*1024;}
    byte[] seal(String id,String value)throws Exception{
        WorkspaceState.profile(id);byte[] plain=value.getBytes(StandardCharsets.UTF_8);if(plain.length>maxPlain)throw new GeneralSecurityException("任务内容过大");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get());byte[] iv=cipher.getIV();if(iv.length!=12)throw new GeneralSecurityException("加密参数不可用");cipher.updateAAD((domain+id).getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(plain);
        return ByteBuffer.allocate(4+iv.length+encrypted.length).put(new byte[]{'H','P','0','1'}).put(iv).put(encrypted).array();
    }
    String open(String id,byte[] value)throws Exception{
        WorkspaceState.profile(id);if(value.length<32||value.length>maxPlain+32||value[0]!='H'||value[1]!='P'||value[2]!='0'||value[3]!='1')throw new GeneralSecurityException("加密任务格式无效");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,keys.get(),new GCMParameterSpec(128,java.util.Arrays.copyOfRange(value,4,16)));cipher.updateAAD((domain+id).getBytes(StandardCharsets.UTF_8));
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(cipher.doFinal(value,16,value.length-16))).toString();
    }
}
