package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputFilter;
import android.view.Gravity;
import android.widget.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;

/** Shared account metadata editor. Existing login environments are immutable. */
final class AccountEditor {
    static void show(Activity activity,StateStore store,String nullableAccountId,Consumer<WorkspaceState> saved) {
        final WorkspaceState initial;
        try{initial=store.load();}catch(Exception e){notice(activity,"无法读取账号记录");return;}
        final WorkspaceState.Account old;
        try{old=nullableAccountId==null?null:initial.find(nullableAccountId);}catch(Exception e){notice(activity,"账号已移除，请重新选择");return;}
        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(activity,24),dp(activity,8),dp(activity,24),0);
        EditText name=field(activity,"账号名称",old==null?"":old.name,40),email=field(activity,"邮箱备注（可选）",old==null?"":old.email,120);
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);fields.addView(name);fields.addView(email);
        LinearLayout colors=new LinearLayout(activity);fields.addView(colors);
        String[] selected={old==null?WorkspaceState.COLORS[initial.accounts.size()%WorkspaceState.COLORS.length]:old.color};List<RadioButton> dots=new ArrayList<>();
        for(String color:WorkspaceState.COLORS){RadioButton dot=new RadioButton(activity);dot.setContentDescription("账号色点 "+color);dot.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(color)));dot.setChecked(color.equals(selected[0]));dot.setGravity(Gravity.CENTER);colors.addView(dot,new LinearLayout.LayoutParams(0,dp(activity,48),1));dots.add(dot);dot.setOnClickListener(v->{selected[0]=color;for(int i=0;i<dots.size();i++)dots.get(i).setChecked(WorkspaceState.COLORS[i].equals(color));});}
        CheckBox independent=new CheckBox(activity);independent.setText("独立语言与时区环境");independent.setChecked(BuildConfig.ENVIRONMENT_CLIENT);
        AutoCompleteTextView language=new AutoCompleteTextView(activity),zone=new AutoCompleteTextView(activity);
        language.setSingleLine(true);language.setHint("语言（BCP 47，例如 zh-CN）");language.setText(Locale.getDefault().toLanguageTag());
        zone.setSingleLine(true);zone.setHint("时区（IANA，例如 Asia/Shanghai）");zone.setText(TimeZone.getDefault().getID());
        language.setThreshold(1);zone.setThreshold(1);
        language.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_dropdown_item_1line,new String[]{"zh-CN","zh-TW","en-US","en-GB","ja-JP","ko-KR","de-DE","fr-FR","es-ES"}));
        String[] zones=java.time.ZoneId.getAvailableZoneIds().toArray(new String[0]);Arrays.sort(zones);zone.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_dropdown_item_1line,zones));
        if(old==null&&BuildConfig.ENVIRONMENT_CLIENT){
            fields.addView(independent);fields.addView(language);fields.addView(zone);
            independent.setOnCheckedChangeListener((button,checked)->{language.setEnabled(checked);zone.setEnabled(checked);});
            TextView explanation=new TextView(activity);explanation.setText("环境创建后保留语言和时区。需要其他环境时请新建账号空间。");fields.addView(explanation);
        }else if(old!=null){TextView explanation=new TextView(activity);explanation.setText(old.isEnvironment()?"语言："+old.language+"\n时区："+old.timeZone+"\n当前登录空间保留，环境不可更改。":"当前登录空间保留，仅修改备注和色点。");fields.addView(explanation);}
        ScrollView scroll=new ScrollView(activity);scroll.addView(fields);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(old==null?"添加账号":"编辑账号").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(name.getText().toString().trim().isEmpty()){name.setError("请填写名称");return;}
            try{
                boolean environment=old==null&&BuildConfig.ENVIRONMENT_CLIENT&&independent.isChecked();
                String locale=environment?EnvironmentConfig.canonicalLanguage(language.getText().toString().trim()):null;
                String timeZone=environment?EnvironmentConfig.canonicalTimeZone(zone.getText().toString().trim()):null;
                WorkspaceState result=store.update(state->{
                    if(nullableAccountId==null){if(environment)state.addEnvironmentAccount(name.getText().toString(),email.getText().toString(),selected[0],locale,timeZone);else state.addAccount(name.getText().toString(),email.getText().toString(),selected[0]);}
                    else{WorkspaceState.Account account=state.find(nullableAccountId);account.name=name.getText().toString().trim();account.email=email.getText().toString().trim();account.color=selected[0];}
                });
                dialog.dismiss();saved.accept(result);
            }catch(Exception e){notice(activity,"操作未保存："+(e.getMessage()==null?"请重试":e.getMessage()));}
        }));dialog.show();
    }
    private static EditText field(Activity activity,String hint,String value,int max){EditText field=new EditText(activity);field.setHint(hint);field.setText(value);field.setSingleLine(true);field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});return field;}
    private static int dp(Activity activity,int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private static void notice(Activity activity,String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private AccountEditor() {}
}
