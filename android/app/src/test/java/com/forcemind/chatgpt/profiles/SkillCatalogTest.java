package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkillCatalogTest {
    private File root(){return new File(System.getProperty("capabilityRoot"));}
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
}
