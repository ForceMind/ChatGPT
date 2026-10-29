package com.forcemind.chatgpt.pocmetrics;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** CI Android 9 shell probe only. Never packaged into an application. */
public final class ProcessMetrics {
    public static void main(String[] args)throws Exception {
        if(android.os.Process.myUid()!=2000||Build.VERSION.SDK_INT!=28||args.length!=0)throw new IllegalStateException("Android 9 CI shell only");
        Process check=new ProcessBuilder("/system/bin/getprop","ro.kernel.qemu").start();
        String qemu;try(BufferedReader reader=new BufferedReader(new InputStreamReader(check.getInputStream()))){qemu=reader.readLine();}
        if(check.waitFor()!=0||!"1".equals(qemu))throw new IllegalStateException("Physical devices refused");
        long hz=Os.sysconf(OsConstants._SC_CLK_TCK),cores=Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN);
        if(hz<1||hz>10000||cores<1||cores>128)throw new IllegalStateException("Invalid actual sysconf values");
        System.out.println("{\"provider\":\"android.system.Os.sysconf\",\"api\":28,\"shellUid\":2000,\"qemu\":true,\"clockTicksPerSecond\":"+hz+",\"logicalCPUs\":"+cores+"}");
    }
}
