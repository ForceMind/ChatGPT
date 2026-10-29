package com.forcemind.chatgpt.environmentpoc;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

public final class DigestEncodingCheck {
    public static void main(String[] args)throws Exception{
        byte[] all=new byte[256];for(int i=0;i<all.length;i++)all[i]=(byte)i;
        for(byte[] bytes:new byte[][]{new byte[0],new byte[]{0,1,15,16,127,-128,-1},all}){
            StringBuilder expected=new StringBuilder();for(byte value:bytes)expected.append(String.format(Locale.ROOT,"%02x",value&255));
            if(!expected.toString().equals(DigestEncoding.hex(bytes)))throw new AssertionError("Encoding mismatch");
        }
        if(!DigestEncoding.hex(MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8))).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))throw new AssertionError("Known SHA vector mismatch");
        Random random=new Random(219);
        for(int n=0;n<100;n++){byte[] bytes=new byte[32];random.nextBytes(bytes);StringBuilder expected=new StringBuilder();for(byte b:bytes)expected.append(String.format(Locale.ROOT,"%02x",b&255));if(!expected.toString().equals(DigestEncoding.hex(bytes)))throw new AssertionError("Random encoding mismatch");}
        System.out.println("Digest encoding known vector, full byte range, leading zero and100 deterministic samples passed");
    }
}
