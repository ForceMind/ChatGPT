package com.forcemind.chatgpt.profiles;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.text.InputFilter;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Explicit native review before creating a normal ChatGPT conversation. */
final class HandoffDialog {
    private final Activity activity;
    private final WorkspaceState state;
    private final WorkspaceState.Account source;
    private final SkillCatalog catalog;
    private final List<JSONObject> files,skills,agents;
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private final AtomicBoolean alive=new AtomicBoolean(true);
    private final String requestId=WorkspaceState.id();
    private Spinner target,skill,agent;
    private EditText instructions,language;
    private CheckBox cross;
    private TextView summary,status;
    private Button attachments;
    private AlertDialog dialog;
    private boolean preparing;

    HandoffDialog(Activity activity,WorkspaceState state,String sourceId,SkillCatalog catalog,List<JSONObject> files,List<String> initial,String skillId)throws Exception{
        this(activity,state,sourceId,catalog,files,initial,skillId,"");
    }
    HandoffDialog(Activity activity,WorkspaceState state,String sourceId,SkillCatalog catalog,List<JSONObject> files,List<String> initial,String skillId,String agentId)throws Exception{
        this.activity=activity;this.state=state;source=state.find(sourceId);this.catalog=catalog;this.skills=catalog.list();this.agents=catalog.agents();this.files=new ArrayList<>();
        for(JSONObject file:files)if(file.optString("status").equals("ready")&&!file.optBoolean("trashed"))this.files.add(file);
        for(String id:initial)if(this.files.stream().anyMatch(f->f.optString("id").equals(id)))selected.add(id);
        show(skillId,agentId);
    }
    private void show(String skillId,String agentId){
        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);int pad=dp(20);fields.setPadding(pad,dp(8),pad,dp(12));
        fields.addView(text("文件来源："+label(source)+"\n将在目标账号新建对话并发送一次。离开前台或任务中断会停止自动处理。"));
        fields.addView(text("目标账号"));target=new Spinner(activity);target.setContentDescription("目标账号");List<String> labels=new ArrayList<>();int initial=0;
        for(int i=0;i<state.accounts.size();i++){labels.add(label(state.accounts.get(i)));if(state.accounts.get(i).id.equals(source.id))initial=i;}
        target.setAdapter(adapter(labels));target.setSelection(initial);fields.addView(target);
        attachments=new Button(activity);attachments.setText("选择附件");fields.addView(attachments);attachments.setOnClickListener(v->chooseFiles());summary=text("");fields.addView(summary);
        cross=new CheckBox(activity);cross.setText("我确认将所选文件发送到另一个账号");cross.setTextColor(Color.BLACK);fields.addView(cross);
        target.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){cross.setChecked(false);refresh();}public void onNothingSelected(AdapterView<?> p){}});
        fields.addView(text("处理 Skill"));skill=new Spinner(activity);skill.setContentDescription("处理 Skill");labels=new ArrayList<>();labels.add("不使用 Skill");int skillIndex=0;
        for(int i=0;i<skills.size();i++){JSONObject s=skills.get(i);labels.add(s.optString("name"));if(s.optString("id").equals(skillId))skillIndex=i+1;}
        skill.setAdapter(adapter(labels));skill.setSelection(skillIndex);fields.addView(skill);
        fields.addView(text("处理角色"));agent=new Spinner(activity);agent.setContentDescription("处理角色");labels=new ArrayList<>();labels.add("不使用角色");int agentIndex=0;
        for(int i=0;i<agents.size();i++){JSONObject role=agents.get(i);labels.add(role.optString("name"));if(role.optString("id").equals(agentId))agentIndex=i+1;}
        agent.setAdapter(adapter(labels));agent.setSelection(agentIndex);fields.addView(agent);
        fields.addView(text("输出语言"));language=new EditText(activity);language.setSingleLine(true);language.setText("简体中文");language.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});fields.addView(language);
        fields.addView(text("补充要求"));instructions=new EditText(activity);instructions.setHint("说明希望如何处理文件，或选择 Skill / 角色");instructions.setMinLines(3);instructions.setMaxLines(6);instructions.setGravity(android.view.Gravity.TOP);instructions.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});
        if(!selected.isEmpty())instructions.setText("请分析这些附件，说明主要内容并给出处理建议。");fields.addView(instructions);
        fields.addView(text("最多 20 个附件，单个 100 MB，合计 200 MB。HTML、SVG 和代码按文本附件提交，保留原件。"));status=text("");fields.addView(status);
        ScrollView scroll=new ScrollView(activity);scroll.addView(fields);
        dialog=new AlertDialog.Builder(activity).setTitle("交给 GPT").setView(scroll).setNegativeButton("取消",null).setPositiveButton("新建对话并发送",null).create();
        dialog.setOnDismissListener(d->alive.set(false));dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->submit()));dialog.show();refresh();
    }
    private void chooseFiles(){
        if(preparing)return;boolean[] checked=new boolean[files.size()];String[] names=new String[files.size()];LinkedHashSet<String> draft=new LinkedHashSet<>(selected);
        for(int i=0;i<files.size();i++){JSONObject file=files.get(i);checked[i]=selected.contains(file.optString("id"));names[i]=file.optString("name")+" · "+android.text.format.Formatter.formatShortFileSize(activity,file.optLong("size"));}
        if(files.isEmpty()){status.setText("此账号还没有已归档文件，可取消后先导入文件。");return;}
        new AlertDialog.Builder(activity).setTitle("选择此账号的附件").setMultiChoiceItems(names,checked,(d,i,value)->{String id=files.get(i).optString("id");if(value)draft.add(id);else draft.remove(id);})
            .setNegativeButton("取消",null).setPositiveButton("确定",(d,w)->{if(draft.size()>20){status.setText("一次最多选择 20 个附件，请重新选择。");return;}selected.clear();selected.addAll(draft);cross.setChecked(false);refresh();}).show();
    }
    private void refresh(){if(summary==null||cross==null)return;StringBuilder names=new StringBuilder();long total=0;for(JSONObject f:files)if(selected.contains(f.optString("id"))){if(names.length()>0)names.append('\n');names.append(f.optString("name"));total+=f.optLong("size");}
        attachments.setText("选择附件（"+selected.size()+"）");summary.setText(selected.isEmpty()?"不带附件，仅发送处理要求":names+"\n合计 "+android.text.format.Formatter.formatShortFileSize(activity,total));
        cross.setVisibility(!selected.isEmpty()&&!state.accounts.get(target.getSelectedItemPosition()).id.equals(source.id)?View.VISIBLE:View.GONE);
    }
    private void submit(){
        if(preparing||!alive.get())return;
        try{
            WorkspaceState.Account destination=state.accounts.get(target.getSelectedItemPosition());int s=skill.getSelectedItemPosition(),a=agent.getSelectedItemPosition();
            JSONObject request=new JSONObject().put("requestId",requestId).put("sourceAccountId",source.id).put("sourceStorageId",source.storageId).put("targetAccountId",destination.id).put("targetStorageId",destination.storageId)
                .put("assetIds",new JSONArray(selected)).put("crossAccountConfirmed",cross.isChecked()).put("skillId",s==0?"":skills.get(s-1).getString("id")).put("agentId",a==0?"":agents.get(a-1).getString("id")).put("instructions",instructions.getText().toString()).put("language",language.getText().toString());
            request=HandoffRequest.normalize(request);HandoffRuntime runtime=HandoffRuntime.get(activity);JSONObject accepted=request;
            preparing=true;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);attachments.setEnabled(false);target.setEnabled(false);skill.setEnabled(false);agent.setEnabled(false);cross.setEnabled(false);instructions.setEnabled(false);language.setEnabled(false);status.setText("正在校验并准备附件，原件保留…");
            runtime.io.execute(()->{
                try{JSONObject launch=runtime.prepare(accepted,catalog,alive::get);
                    runtime.handler.post(()->{if(!alive.get()||activity.isFinishing()||activity.isDestroyed()){cancelReady(runtime);return;}
                        try{activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("handoffJob",launch.getString("id")).putExtra("handoffToken",launch.getString("token")));dialog.dismiss();activity.finish();}
                        catch(Exception e){cancelReady(runtime);status.setText("无法打开目标网页，任务已停止，请重新发起。");}
                    });
                }catch(Exception e){runtime.handler.post(()->{if(alive.get()){status.setText(e.getMessage()==null?"任务准备失败，请关闭后重新发起。":e.getMessage()+"。请关闭后重新发起。");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭");}});}
            });
        }catch(Exception e){status.setText(e.getMessage()==null?"请检查目标账号、附件和处理要求。":e.getMessage());}
    }
    private void cancelReady(HandoffRuntime runtime){runtime.io.execute(()->{try{runtime.cancel(requestId,source.id);}catch(Exception ignored){}});}
    void close(){alive.set(false);if(dialog!=null)dialog.dismiss();}
    private String label(WorkspaceState.Account a){return a.name+(a.email.isEmpty()?"":" · "+a.email);}
    private ArrayAdapter<String> adapter(List<String> values){ArrayAdapter<String> result=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,values);result.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return result;}
    private TextView text(String value){TextView text=new TextView(activity);text.setText(value);text.setTextColor(Color.rgb(35,35,35));StudioTypography.text(text,14);text.setPadding(0,dp(8),0,dp(4));return text;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
}
