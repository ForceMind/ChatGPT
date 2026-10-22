package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class WorkspaceFilesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private void write(String name,String data)throws Exception{Files.write(new File(temporary.getRoot(),name).toPath(),data.getBytes(StandardCharsets.UTF_8));}
    @Test public void validPrimaryDoesNotReviveAccountsFromAnOlderBackup()throws Exception{
        WorkspaceState old=WorkspaceState.fresh();String removed=old.current().id;
        old.addAccount("保留账号","",WorkspaceState.COLORS[1]);write("workspace.backup.json",old.json().toString());
        old.removeAccount(old.find(removed));write("workspace.json",old.json().toString());
        assertFalse(WorkspaceFiles.contains(temporary.getRoot(),removed));assertEquals(1,WorkspaceFiles.read(temporary.getRoot()).accounts.size());
    }
    @Test public void anAtomicWriteGapCanReadTheValidBackupWithoutRepairingFiles()throws Exception{
        WorkspaceState good=WorkspaceState.fresh();write("workspace.backup.json",good.json().toString());write("workspace.json","{partial");
        assertTrue(WorkspaceFiles.contains(temporary.getRoot(),good.activeAccount));
        assertEquals("{partial",new String(Files.readAllBytes(new File(temporary.getRoot(),"workspace.json").toPath()),StandardCharsets.UTF_8));
    }
    @Test public void missingStateNeverCreatesAnAccountAsASideEffect()throws Exception{
        assertThrows(Exception.class,()->WorkspaceFiles.read(temporary.getRoot()));assertEquals(0,temporary.getRoot().list().length);
    }
}
