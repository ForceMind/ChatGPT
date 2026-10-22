package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Read-only snapshots for child screens/jobs; never creates or repairs account data. */
final class WorkspaceFiles {
    static WorkspaceState read(File filesDir) throws Exception {
        File base=filesDir.getCanonicalFile();Exception last=null;
        for(String name:new String[]{"workspace.json","workspace.json.bak","workspace.backup.json","workspace.backup.json.bak"}){
            File file=new File(base,name);
            try{
                if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>4*1024*1024)continue;
                return WorkspaceState.parse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            }catch(Exception e){last=e;}
        }
        throw new IOException("账号记录暂不可读，请返回网页重试",last);
    }
    static boolean contains(File filesDir,String accountId){
        try{read(filesDir).find(accountId);return true;}catch(Exception e){return false;}
    }
}
