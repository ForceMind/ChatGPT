package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkillCatalogTest {
    private File root(){return new File(System.getProperty("capabilityRoot"));}
    private SkillCatalog extended()throws Exception{return new SkillCatalog(new FileInputStream(new File(root(),"manifest.json")),new FileInputStream(new File(root(),"skills.json")),new FileInputStream(new File(root(),"desktop-manifest.json")),new FileInputStream(new File(root(),"desktop-capabilities.json")));}
    @Test public void mobileCatalogContainsAllProjectSkillsAndUsefulPrompts() throws Exception {
        SkillCatalog c=new SkillCatalog(new FileInputStream(new File(root(),"manifest.json")),new FileInputStream(new File(root(),"skills.json")));
        assertEquals(120,c.list().size());
        for(org.json.JSONObject skill:c.list()){
            String text=SkillCatalog.prompt(skill);assertTrue(text.contains("处理步骤"));assertTrue(text.contains("输出结构"));assertTrue(text.contains("质量检查"));
            assertTrue(text.contains(skill.getString("name")));
            assertTrue(text.contains(skill.getString("instructions")));
            for(String key:new String[]{"steps","outputSections","checks"})for(int i=0;i<skill.getJSONArray(key).length();i++)assertTrue(skill.getString("id")+" missing "+key,text.contains(skill.getJSONArray(key).getString(i)));
        }
    }
    @Test public void changedResourcesNeverBecomeAnEmptyOrPartialLibrary() throws Exception {
        byte[] skills=Files.readAllBytes(new File(root(),"skills.json").toPath());skills[skills.length/2]^=1;
        assertThrows(Exception.class,()->new SkillCatalog(new FileInputStream(new File(root(),"manifest.json")),new ByteArrayInputStream(skills)));
    }
    @Test public void projectExtensionHasAllSkillsAndOriginalInternetRolesWithFullTaskInstructions()throws Exception{
        SkillCatalog c=extended();assertEquals(121,c.list().size());assertEquals(32,c.agents().size());Set<String> ids=new HashSet<>();int priority=0;
        for(int i=0;i<c.agents().size();i++){JSONObject role=c.agents().get(i);assertTrue(ids.add(role.getString("id")));assertEquals(i<24,role.getBoolean("internetPriority"));if(role.getBoolean("internetPriority"))priority++;String prompt=c.task("",role.getString("id")).getString("prompt");assertTrue(prompt.contains(role.getString("instructions")));assertTrue(prompt.contains("不授予其他账号"));}
        assertEquals(24,priority);assertThrows(Exception.class,()->c.agent("agent-001"));assertThrows(Exception.class,()->c.agent("studio-agent-999"));
        JSONObject combined=c.task("skill-121","studio-agent-004");for(String text:new String[]{"baseHash","boardId","nodeId","set-node",c.agent("studio-agent-004").getString("instructions")})assertTrue(combined.getString("prompt").contains(text));
        String original=c.agent("studio-agent-001").getString("instructions");c.agents().get(0).put("instructions","changed");assertEquals(original,c.agent("studio-agent-001").getString("instructions"));
    }
    @Test public void wellHashedButInvalidExtensionsCannotRebindLegacyIdsOrChangeCounts()throws Exception{
        byte[] manifest=Files.readAllBytes(new File(root(),"desktop-manifest.json").toPath());JSONObject original=new JSONObject(new String(Files.readAllBytes(new File(root(),"desktop-capabilities.json").toPath()),StandardCharsets.UTF_8));
        List<java.util.function.Consumer<JSONObject>> changes=Arrays.asList(v->{try{v.getJSONArray("agents").getJSONObject(0).put("id","agent-001");}catch(Exception e){throw new RuntimeException(e);}},v->{try{v.getJSONArray("agents").getJSONObject(1).put("id","studio-agent-001");}catch(Exception e){throw new RuntimeException(e);}},v->{try{v.getJSONArray("skills").getJSONObject(0).put("id","skill-001");}catch(Exception e){throw new RuntimeException(e);}},v->{try{v.getJSONArray("agents").getJSONObject(0).put("internetPriority",false);}catch(Exception e){throw new RuntimeException(e);}});
        for(java.util.function.Consumer<JSONObject> change:changes){JSONObject value=new JSONObject(original.toString());change.accept(value);byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);JSONObject metadata=new JSONObject(new String(manifest,StandardCharsets.UTF_8)).put("sha256",HandoffRequest.hash(bytes));assertThrows(Exception.class,()->new SkillCatalog(new FileInputStream(new File(root(),"manifest.json")),new FileInputStream(new File(root(),"skills.json")),new ByteArrayInputStream(metadata.toString().getBytes(StandardCharsets.UTF_8)),new ByteArrayInputStream(bytes)));}
        JSONObject traversal=new JSONObject(new String(manifest,StandardCharsets.UTF_8)).put("file","../skills.json");assertThrows(Exception.class,()->new SkillCatalog(new FileInputStream(new File(root(),"manifest.json")),new FileInputStream(new File(root(),"skills.json")),new ByteArrayInputStream(traversal.toString().getBytes(StandardCharsets.UTF_8)),new FileInputStream(new File(root(),"desktop-capabilities.json"))));
    }
}
