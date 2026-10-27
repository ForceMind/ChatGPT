package com.forcemind.chatgpt.environmentpoc;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Only the independent PoC's synthetic session records. Never accesses app data. */
final class RecoveryStore {
    private final File root;
    private final SecretKey key;
    RecoveryStore(Context context)throws Exception{
        root=new File(context.getFilesDir(),"recovery-records");
        if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("Cannot create experiment store");
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        String alias="studio-independent-recovery-poc-v1";
        if(!keys.containsAlias(alias)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        key=(SecretKey)keys.getKey(alias,null);
    }
    private AtomicFile file(String account,String tab){
        if(!account.matches("[ABC]")||!tab.matches("(?:workspace|[01])"))throw new IllegalArgumentException("Invalid synthetic identity");
        return new AtomicFile(new File(root,account+"-"+tab+".bin"));
    }
    JSONObject read(String account,String tab)throws Exception{
        AtomicFile file=file(account,tab);if(!file.getBaseFile().exists())return null;
        byte[] bytes=file.readFully();if(bytes.length<29||bytes.length>1024*1024)throw new IllegalStateException("Invalid record size; original preserved");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));
        cipher.updateAAD(("recovery-poc-v1/"+account+"/"+tab).getBytes(StandardCharsets.UTF_8));
        JSONObject result=new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8));
        if(result.getInt("schema")!=1||!result.getString("account").equals(account)||!result.getString("tab").equals(tab))throw new IllegalStateException("Record identity mismatch");
        return result;
    }
    void write(String account,String tab,JSONObject data)throws Exception{
        data.put("schema",1).put("account",account).put("tab",tab);
        byte[] plain=data.toString().getBytes(StandardCharsets.UTF_8);if(plain.length>1024*1024-28)throw new IllegalArgumentException("Record too large");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(("recovery-poc-v1/"+account+"/"+tab).getBytes(StandardCharsets.UTF_8));
        AtomicFile file=file(account,tab);FileOutputStream out=null;
        try{out=file.startWrite();out.write(cipher.getIV());out.write(cipher.doFinal(plain));file.finishWrite(out);}
        catch(Exception error){if(out!=null)file.failWrite(out);throw error;}
    }
}
