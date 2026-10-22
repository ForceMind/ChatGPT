package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

final class SkillCatalog {
    private final List<JSONObject> skills = new ArrayList<>();
    SkillCatalog(InputStream manifestInput, InputStream skillsInput) throws Exception {
        byte[] manifestBytes,skillBytes;
        try(InputStream m=manifestInput;InputStream s=skillsInput){manifestBytes=read(m);skillBytes=read(s);}
        JSONObject manifest = new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8));
        if(manifest.getInt("schema")!=2 || !"project".equals(manifest.getString("source"))
                || manifest.getInt("skillCount")!=120 || manifest.getInt("agentCount")!=0
                || manifest.getJSONArray("agents").length()!=0 || manifest.getJSONObject("files").length()!=1
                || !"skills.json".equals(manifest.getString("skillsFile"))) throw new IOException("能力目录无效");
        StringBuilder digest=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(skillBytes))digest.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!digest.toString().equals(manifest.getJSONObject("files").getString("skills.json")))throw new IOException("能力文件摘要不一致");
        JSONObject source=new JSONObject(new String(skillBytes,StandardCharsets.UTF_8));JSONArray list=source.getJSONArray("skills");Set<String> ids=new HashSet<>();
        if(source.getInt("schema")!=1||list.length()!=120)throw new IOException("Skill 数量或版本无效");
        for(int i=0;i<list.length();i++){
            JSONObject skill=list.getJSONObject(i);
            if(!ids.add(skill.getString("id"))||skill.getString("name").trim().isEmpty()||skill.getString("instructions").trim().isEmpty()
                    ||skill.getJSONArray("steps").length()<1||skill.getJSONArray("checks").length()<1)throw new IOException("Skill 定义无效");
            skills.add(skill);
        }
        skills.sort((a,b)->Boolean.compare(b.optBoolean("internetPriority"),a.optBoolean("internetPriority")));
    }
    private static byte[] read(InputStream input) throws Exception {
        if(input==null)throw new IOException("能力文件缺失");
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            InputStream in=input;
            byte[] buffer=new byte[32768];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>6*1024*1024)throw new IOException("能力文件过大");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }
    List<JSONObject> list(){return new ArrayList<>(skills);}
    static String prompt(JSONObject skill) throws Exception {
        StringBuilder text=new StringBuilder("任务：").append(skill.getString("name")).append("\n\n").append(skill.getString("instructions")).append("\n\n输入材料：\n");
        JSONArray inputs=skill.getJSONArray("inputs");for(int i=0;i<inputs.length();i++)text.append("- ").append(inputs.get(i).toString()).append('\n');
        text.append("\n参数：\n").append(skill.getJSONArray("parameters").toString(2)).append("\n\n处理步骤：\n");
        JSONArray steps=skill.getJSONArray("steps");for(int i=0;i<steps.length();i++)text.append(i+1).append(". ").append(steps.getString(i)).append('\n');
        text.append("\n输出结构：\n");
        JSONArray sections=skill.getJSONArray("outputSections");for(int i=0;i<sections.length();i++)text.append("- ").append(sections.getString(i)).append('\n');
        text.append("\n质量检查：\n");JSONArray checks=skill.getJSONArray("checks");for(int i=0;i<checks.length();i++)text.append("- ").append(checks.getString(i)).append('\n');
        text.append("\n示例：\n").append(skill.getJSONArray("examples").toString(2));
        return text.toString();
    }
}
