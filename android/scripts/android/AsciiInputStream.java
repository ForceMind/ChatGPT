package com.forcemind.chatgpt.pocinput;

import android.os.Build;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** CI shell only. One JVM, one original AsciiInput invocation per acknowledged input line. */
public final class AsciiInputStream {
    private static void state(String phase,int dispatch,String extra){
        System.out.println("{\"provider\":\"ci-native-ascii-stream\",\"phase\":\""+phase+"\",\"pid\":"+android.os.Process.myPid()+",\"uid\":"+android.os.Process.myUid()+",\"api\":"+Build.VERSION.SDK_INT+",\"dispatch\":"+dispatch+extra+"}");
        System.out.flush();
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=0||android.os.Process.myUid()!=2000||(Build.VERSION.SDK_INT!=28&&Build.VERSION.SDK_INT!=35))throw new IllegalStateException("Owned API28/API35 CI shell stream only");
        Process check=new ProcessBuilder("/system/bin/getprop","ro.kernel.qemu").start();String qemu;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(check.getInputStream(),StandardCharsets.UTF_8))){qemu=reader.readLine();}
        if(check.waitFor()!=0||!"1".equals(qemu))throw new IllegalStateException("Physical devices refused");
        int dispatch=0;
        state("ready",dispatch,",\"qemu\":true");
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            String character;
            while((character=input.readLine())!=null){
                if(dispatch>=128||!character.matches("[A-Za-z0-9-]"))throw new IllegalArgumentException("Exactly one owned ASCII character per dispatch");
                state("dispatch",dispatch,",\"unicodeChar\":"+(int)character.charAt(0));
                // Do not rewrite or wrap the original native keyboard receipt.
                AsciiInput.main(new String[]{character});
                System.out.flush();
                state("done",dispatch,"");
                dispatch++;
            }
            state("closed",dispatch,",\"complete\":true");
        }catch(Exception error){
            state("failed",dispatch,",\"errorClass\":\""+error.getClass().getName()+"\"");
            throw error;
        }
    }
}
