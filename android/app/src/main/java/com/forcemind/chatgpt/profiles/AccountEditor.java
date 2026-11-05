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
        for(String color:WorkspaceState.COLORS){RadioButton dot=new RadioButton(activity);StudioTypography.text(dot,15);dot.setMinHeight(StudioTypography.controlHeight(activity));dot.setContentDescription("账号色点 "+color);dot.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(color)));dot.setChecked(color.equals(selected[0]));dot.setGravity(Gravity.CENTER);colors.addView(dot,new LinearLayout.LayoutParams(0,StudioTypography.controlHeight(activity),1));dots.add(dot);dot.setOnClickListener(v->{selected[0]=color;for(int i=0;i<dots.size();i++)dots.get(i).setChecked(WorkspaceState.COLORS[i].equals(color));});}
        CheckBox independent=new CheckBox(activity);independent.setText("独立语言与时区环境");independent.setChecked(BuildConfig.ENVIRONMENT_CLIENT);StudioTypography.text(independent,15);independent.setTextColor(StudioTypography.INK);independent.setMinHeight(StudioTypography.controlHeight(activity));
        String[] knownTags={"zh-CN","zh-TW","en-US","en-GB","ja-JP","ko-KR","de-DE","fr-FR","es-ES"};
        List<String> tags=new ArrayList<>(Arrays.asList(knownTags));
        List<String> languageNames=new ArrayList<>(Arrays.asList("简体中文","繁體中文","English (US)","English (UK)","日本語","한국어","Deutsch","Français","Español"));
        String deviceLanguage=android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).toLanguageTag();int selectedLanguage=-1;
        for(int i=0;i<tags.size();i++)if(tags.get(i).equalsIgnoreCase(deviceLanguage)){selectedLanguage=i;break;}
        if(selectedLanguage<0){tags.add(deviceLanguage);languageNames.add("本机语言（"+deviceLanguage+"）");selectedLanguage=tags.size()-1;}
        Spinner language=new Spinner(activity);language.setContentDescription("网页语言");language.setMinimumHeight(StudioTypography.controlHeight(activity));
        language.setAdapter(choices(activity,android.R.layout.simple_spinner_item,languageNames));language.setSelection(selectedLanguage);
        AutoCompleteTextView zone=new AutoCompleteTextView(activity);zone.setSingleLine(true);zone.setHint("网页时区，例如 Asia/Shanghai");zone.setText(TimeZone.getDefault().getID());zone.setThreshold(1);StudioTypography.text(zone,15);zone.setTextColor(StudioTypography.INK);zone.setMinHeight(StudioTypography.controlHeight(activity));
        String[] zones=java.time.ZoneId.getAvailableZoneIds().toArray(new String[0]);Arrays.sort(zones);zone.setAdapter(choices(activity,android.R.layout.simple_dropdown_item_1line,Arrays.asList(zones)));
        if(old==null&&BuildConfig.ENVIRONMENT_CLIENT){
            fields.addView(independent);fields.addView(label(activity,"网页语言"));fields.addView(language);fields.addView(label(activity,"网页时区"));fields.addView(zone);
            independent.setOnCheckedChangeListener((button,checked)->{language.setEnabled(checked);zone.setEnabled(checked);});
            TextView explanation=label(activity,"");StudioTypography.meta(explanation);explanation.setText("环境创建后保留语言和时区。需要其他环境时请新建账号空间。");fields.addView(explanation);
        }else if(old!=null){TextView explanation=label(activity,"");StudioTypography.meta(explanation);explanation.setText(old.isEnvironment()?"语言："+old.language+"\n时区："+old.timeZone+"\n当前登录空间保留，环境不可更改。":"当前登录空间保留，仅修改备注和色点。");fields.addView(explanation);}
        ScrollView scroll=new ScrollView(activity);scroll.addView(fields);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(old==null?"添加账号":"编辑账号").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->{
            StudioTypography.button(dialog.getButton(AlertDialog.BUTTON_POSITIVE));StudioTypography.button(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(name.getText().toString().trim().isEmpty()){name.setError("请填写名称");return;}
            try{
                boolean environment=old==null&&BuildConfig.ENVIRONMENT_CLIENT&&independent.isChecked();
                String locale=environment?EnvironmentConfig.canonicalLanguage(tags.get(language.getSelectedItemPosition())):null;
                final String timeZone;
                try{timeZone=environment?EnvironmentConfig.canonicalTimeZone(zone.getText().toString().trim()):null;}
                catch(IllegalArgumentException invalidZone){zone.setError("请选择有效的网页时区，例如 Asia/Shanghai");return;}
                WorkspaceState result=store.update(state->{
                    if(nullableAccountId==null){if(environment)state.addEnvironmentAccount(name.getText().toString(),email.getText().toString(),selected[0],locale,timeZone);else state.addAccount(name.getText().toString(),email.getText().toString(),selected[0]);}
                    else{WorkspaceState.Account account=state.find(nullableAccountId);account.name=name.getText().toString().trim();account.email=email.getText().toString().trim();account.color=selected[0];}
                });
                dialog.dismiss();saved.accept(result);
            }catch(Exception e){notice(activity,"操作未保存："+(e.getMessage()==null?"请重试":e.getMessage()));}
        });});dialog.show();
    }
    private static EditText field(Activity activity,String hint,String value,int max){EditText field=new EditText(activity);field.setHint(hint);field.setText(value);field.setSingleLine(true);field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});StudioTypography.text(field,15);field.setTextColor(StudioTypography.INK);field.setMinHeight(StudioTypography.controlHeight(activity));return field;}
    private static TextView label(Activity activity,String value){TextView text=new TextView(activity);text.setText(value);StudioTypography.text(text,15);text.setTextColor(StudioTypography.INK);text.setPadding(0,dp(activity,8),0,dp(activity,4));return text;}
    private static ArrayAdapter<String> choices(Activity activity,int row,List<String> values){
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,row,values){
            private android.view.View readable(android.view.View view){if(view instanceof TextView){TextView text=(TextView)view;StudioTypography.text(text,15);text.setTextColor(StudioTypography.INK);text.setMinHeight(StudioTypography.controlHeight(activity));text.setGravity(Gravity.CENTER_VERTICAL);text.setPadding(dp(activity,12),dp(activity,8),dp(activity,12),dp(activity,8));}return view;}
            @Override public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){return readable(super.getView(position,recycled,parent));}
            @Override public android.view.View getDropDownView(int position,android.view.View recycled,android.view.ViewGroup parent){return readable(super.getDropDownView(position,recycled,parent));}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return adapter;
    }
    private static int dp(Activity activity,int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private static void notice(Activity activity,String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private AccountEditor() {}
}
