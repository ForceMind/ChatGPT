package com.forcemind.chatgpt.profiles;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import java.util.LinkedHashSet;
import java.util.Set;

final class StateStore {
    private final AtomicFile file;
    private final File backup;
    private final AtomicFile revoked;
    StateStore(File directory) { file = new AtomicFile(new File(directory, "workspace.json")); backup = new File(directory, "workspace.backup.json"); revoked = new AtomicFile(new File(directory, "revoked-profiles.json")); }
    WorkspaceState load() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) {
            if (backup.exists()) return protect(readBackup());
            WorkspaceState fresh = WorkspaceState.fresh(); save(fresh); return fresh;
        }
        byte[] original = file.readFully();
        try { return protect(WorkspaceState.parse(new String(original, StandardCharsets.UTF_8))); }
        catch (Exception e) {
            if (!backup.exists()) throw new IOException("账号记录损坏，已停止启动以保护登录空间。请保留应用数据并反馈。", e);
            WorkspaceState recovered = protect(readBackup());
            Files.write(new File(file.getBaseFile().getParent(), "workspace.corrupt-" + System.currentTimeMillis() + ".json").toPath(), original);
            write(file, recovered.json().toString().getBytes(StandardCharsets.UTF_8)); return recovered;
        }
    }
    private WorkspaceState readBackup() throws Exception { return WorkspaceState.parse(new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8)); }
    private Set<String> revokedIds() throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        if (revoked.getBaseFile().exists() || new File(revoked.getBaseFile() + ".bak").exists()) {
            JSONArray list = new JSONArray(new String(revoked.readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < list.length(); i++) { String id = list.getString(i); WorkspaceState.profile(id); ids.add(id); }
        }
        return ids;
    }
    void revoke(String storageId) throws Exception {
        WorkspaceState.profile(storageId); Set<String> ids = revokedIds(); ids.add(storageId);
        JSONArray list = new JSONArray(); for (String id : ids) list.put(id);
        write(revoked, list.toString().getBytes(StandardCharsets.UTF_8));
    }
    private WorkspaceState protect(WorkspaceState state) throws Exception {
        for (String id : revokedIds()) {
            for (WorkspaceState.Account a : state.accounts) if (a.storageId.equals(id)) state.clearAccount(a);
            // Repeated deletion of an absent profile is harmless. Never restore a revoked profile.
            if (!state.pendingDeletes.contains(id)) state.pendingDeletes.add(id);
        }
        state.validate(); return state;
    }
    void save(WorkspaceState state) throws Exception {
        Set<String> revokedIds = revokedIds();
        for (WorkspaceState.Account a : state.accounts) if (revokedIds.contains(a.storageId)) throw new IOException("已撤销的空间不可重新启用");
        byte[] bytes = state.json().toString().getBytes(StandardCharsets.UTF_8);
        if (file.getBaseFile().exists()) {
            byte[] previous = file.readFully();
            WorkspaceState.parse(new String(previous, StandardCharsets.UTF_8));
            write(new AtomicFile(backup), previous);
        }
        write(file, bytes);
    }
    private static void write(AtomicFile file, byte[] data) throws IOException {
        FileOutputStream stream = file.startWrite();
        try { stream.write(data); file.finishWrite(stream); }
        catch (Exception e) { file.failWrite(stream); throw e; }
    }
}
