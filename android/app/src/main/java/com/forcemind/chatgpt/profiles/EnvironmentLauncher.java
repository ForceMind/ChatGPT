package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;

final class EnvironmentLauncher {
    static final String ACCOUNT_ID = "accountId";
    static void open(Activity activity,String accountId) {
        try{
            WorkspaceState state=new StateStore(activity.getFilesDir()).update(latest->{
                WorkspaceState.Account target=latest.find(accountId);
                if(target.isEnvironment()&&!BuildConfig.ENVIRONMENT_CLIENT)throw new IllegalStateException("当前版本不支持独立环境，请使用环境客户端打开");
                latest.activeAccount=target.id;
            });
            WorkspaceState.Account account=state.find(accountId);
            final Intent intent;
            if(account.isEnvironment()){
                EnvironmentConfig.validateSlot(account.environmentSlot);
                intent=new Intent().setComponent(new ComponentName(activity.getPackageName(),"com.forcemind.chatgpt.profiles.EnvironmentBrowserActivity$Slot"+account.environmentSlot));
            }else intent=new Intent(activity,MainActivity.class);
            intent.putExtra(ACCOUNT_ID,account.id).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
        }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("无法读取账号环境",e);}
    }
    private EnvironmentLauncher() {}
}
