package com.forcemind.chatgpt.profiles;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** No-op in human builds. CI may observe real local exports, never supply bytes or authorization. */
final class EnvironmentLocalExportChecks {
    interface Probe {
        void stage(String stage,String operation,String accountId,String tabId,long bytes);
        void beforePublish(String operation,String accountId,String tabId,long bytes,BooleanSupplier valid)throws IOException;
    }
    private static volatile Probe probe;
    static void install(Probe value){if(!BuildConfig.ENVIRONMENT_TEST||!BuildConfig.ENVIRONMENT_NETWORK_TEST)throw new SecurityException("Local export probe disabled");probe=value;}
    static void stage(String stage,String operation,String accountId,String tabId,long bytes){Probe current=probe;if(current!=null)current.stage(stage,operation,accountId,tabId,bytes);}
    static void beforePublish(String operation,String accountId,String tabId,long bytes,BooleanSupplier valid)throws IOException{Probe current=probe;if(current!=null)current.beforePublish(operation,accountId,tabId,bytes,valid);}
    static void initialize(java.io.File files){
        if(!BuildConfig.ENVIRONMENT_CLIENT)return;
        try{java.lang.reflect.Method method=Class.forName("com.forcemind.chatgpt.profiles.CIBuild").getDeclaredMethod("installLocalExportProbe",java.io.File.class);method.setAccessible(true);method.invoke(null,files);}
        catch(ReflectiveOperationException invalid){if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)android.util.Log.e("StudioLocalExport","probe-bootstrap-failed",invalid);throw new IllegalStateException("Local export build policy unavailable",invalid);}
    }
    private EnvironmentLocalExportChecks(){}
}
