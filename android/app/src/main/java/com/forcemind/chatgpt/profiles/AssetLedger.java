package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Account-owned copies only; source paths and login tokens never enter the index. */
final class AssetLedger {
    static final long MAX_FILE = 256L * 1024 * 1024;
    private static final int MAX_INDEX = 16 * 1024 * 1024, MAX_RECORDS = 5000;
    private static final Object LOCK = new Object();
    private static final Set<String> STATUSES = new HashSet<>(Arrays.asList("processing","ready","interrupted","failed","deleting"));
    private static final Set<String> SOURCES = new HashSet<>(Arrays.asList("import","download","conversion"));
    private static final Set<String> ACTIVE_IMPORTS = new HashSet<>();
    private final File root, directory, index, backup;
    final String accountId;

    AssetLedger(File filesDir, String accountId) throws Exception {
        WorkspaceState.profile(accountId); this.accountId = accountId;
        File base = filesDir.getCanonicalFile();
        root = new File(base, "assets"); directory = new File(root, accountId);
        index = new File(directory, "index.json"); backup = new File(directory, "index.backup.json");
        synchronized (LOCK) {
            safeDirectory(root); safeDirectory(directory);
            JSONObject value = read();
            {
                boolean changed = false;
                JSONArray records = value.getJSONArray("records");
                for (int i = 0; i < records.length(); i++) {
                    JSONObject a = records.getJSONObject(i);
                    if (a.getString("status").equals("processing") && !ACTIVE_IMPORTS.contains(directory.getPath()+":"+a.getString("id"))) { a.put("status", "interrupted"); changed = true; }
                }
                if (changed) write(value);
            }
        }
    }
    static String filename(String value) {
        String name = value == null ? "" : value.replace('\\', '/');
        name = Normalizer.normalize(name.substring(name.lastIndexOf('/') + 1), Normalizer.Form.NFC)
                .replaceAll("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069<>:\"|?*]", "_").trim().replaceAll("[. ]+$", "");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "未命名文件";
        if (name.matches("(?i)^(con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³]|conin\\$|conout\\$)(?:\\..*)?$")) name = "_" + name;
        int dot = name.lastIndexOf('.');
        String suffix = dot > 0 && name.substring(dot).getBytes(StandardCharsets.UTF_8).length <= 24 ? name.substring(dot) : "";
        String body = suffix.isEmpty() ? name : name.substring(0,dot);
        StringBuilder out = new StringBuilder(); int bytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        for (int i = 0; i < body.length();) {
            int cp = body.codePointAt(i); String c = new String(Character.toChars(cp)); int count = c.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + count > 180) break; out.append(c); bytes += count; i += Character.charCount(cp);
        }
        return out.append(suffix).toString();
    }
    private static String inferredMime(String name) {
        String value=name.toLowerCase(Locale.ROOT);
        String[][] types={{".png","image/png"},{".jpg","image/jpeg"},{".jpeg","image/jpeg"},{".webp","image/webp"},{".gif","image/gif"},{".avif","image/avif"},{".svg","image/svg+xml"},{".pdf","application/pdf"},{".html","text/html"},{".htm","text/html"},{".md","text/markdown"},{".txt","text/plain"},{".json","application/json"},{".csv","text/csv"}};
        for(String[] type:types)if(value.endsWith(type[0]))return type[1];return "application/octet-stream";
    }
    private static void safeDirectory(File dir) throws Exception {
        if (Files.isSymbolicLink(dir.toPath())) throw new IOException("资产目录不能是符号链接");
        if (!dir.exists() && !dir.mkdir()) throw new IOException("无法创建资产目录");
        if (!dir.isDirectory() || !dir.getCanonicalFile().equals(dir.getAbsoluteFile())) throw new IOException("资产目录无效");
    }
    private void directories() throws Exception { safeDirectory(root); safeDirectory(directory); }
    private void regular(File file, long limit) throws Exception {
        directories();
        if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !file.getCanonicalFile().equals(file.getAbsoluteFile()) || file.length() > limit) throw new IOException("资产文件路径或大小无效");
    }
    private File file(String id, boolean partial) {
        WorkspaceState.profile(id); return new File(directory, id + (partial ? ".part" : ".bin"));
    }
    private JSONObject validate(byte[] bytes) throws Exception {
        if (bytes.length > MAX_INDEX) throw new IOException("资产索引过大");
        JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (value.getInt("schema") != 1 || !accountId.equals(value.getString("accountId"))) throw new IOException("资产索引归属无效");
        JSONArray records = value.getJSONArray("records");
        if (records.length() > MAX_RECORDS) throw new IOException("资产数量超过上限");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject a = records.getJSONObject(i); String id = a.getString("id"); WorkspaceState.profile(id);
            if (!seen.add(id) || !accountId.equals(a.getString("accountId")) || !filename(a.getString("name")).equals(a.getString("name"))
                    || !(a.get("size") instanceof Number) || a.getDouble("size") != a.getLong("size") || a.getLong("size") < 0 || a.getLong("size") > MAX_FILE || !(a.get("createdAt") instanceof Number) || a.getDouble("createdAt") != a.getLong("createdAt") || a.getLong("createdAt") < 0
                    || !STATUSES.contains(a.getString("status"))
                    || !(a.get("trashed") instanceof Boolean) || !(a.get("favorite") instanceof Boolean)
                    || a.getString("mime").length() > 150 || !a.getString("mime").matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") || !SOURCES.contains(a.getString("source"))) throw new IOException("资产索引格式无效");
            if (a.getString("status").equals("ready") && !a.getString("sha256").matches("[a-f0-9]{64}")) throw new IOException("资产摘要无效");
            if (a.has("parentId")) WorkspaceState.profile(a.getString("parentId"));
        }
        return value;
    }
    private byte[] bytes(File file) throws Exception { regular(file, MAX_INDEX); return Files.readAllBytes(file.toPath()); }
    private JSONObject read() throws Exception {
        directories();
        if (!index.exists()) {
            if (backup.exists()) { JSONObject restored = validate(bytes(backup)); atomic(index, restored.toString().getBytes(StandardCharsets.UTF_8)); return restored; }
            String[] files = directory.list((dir, name) -> name.endsWith(".bin") || name.endsWith(".part"));
            if (files == null || files.length > 0) throw new IOException("资产索引缺失，已保留文件并停止创建空库");
            JSONObject fresh = new JSONObject().put("schema", 1).put("accountId", accountId).put("records", new JSONArray());
            atomic(index, fresh.toString().getBytes(StandardCharsets.UTF_8)); return fresh;
        }
        try { return validate(bytes(index)); }
        catch (Exception invalid) {
            if (!backup.exists()) throw new IOException("资产索引损坏，请保留应用数据", invalid);
            JSONObject restored = validate(bytes(backup));
            if (Files.isSymbolicLink(index.toPath())) throw invalid;
            Files.move(index.toPath(), new File(directory, "index.corrupt-" + UUID.randomUUID() + ".json").toPath(), StandardCopyOption.ATOMIC_MOVE);
            atomic(index, restored.toString().getBytes(StandardCharsets.UTF_8)); return restored;
        }
    }
    private void atomic(File destination, byte[] bytes) throws Exception {
        directories();
        if (bytes.length > MAX_INDEX || Files.isSymbolicLink(destination.toPath())) throw new IOException("资产索引路径或大小无效");
        File temp = new File(directory, destination.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) channel.write(data); channel.force(true);
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private void write(JSONObject value) throws Exception {
        byte[] body = value.toString().getBytes(StandardCharsets.UTF_8); validate(body);
        if (index.exists()) { byte[] previous = bytes(index); validate(previous); atomic(backup, previous); }
        atomic(index, body);
    }
    private JSONObject record(JSONObject ledger, String id) throws Exception {
        WorkspaceState.profile(id); JSONArray records = ledger.getJSONArray("records");
        for (int i = 0; i < records.length(); i++) if (records.getJSONObject(i).getString("id").equals(id)) return records.getJSONObject(i);
        throw new IOException("文件不属于当前账号或已移除");
    }
    List<JSONObject> list() throws Exception {
        synchronized (LOCK) {
            JSONArray records = read().getJSONArray("records"); List<JSONObject> out = new ArrayList<>();
            for (int i = 0; i < records.length(); i++) out.add(new JSONObject(records.getJSONObject(i).toString()));
            out.sort((a,b) -> Long.compare(b.optLong("createdAt"), a.optLong("createdAt"))); return out;
        }
    }
    JSONObject get(String id) throws Exception { synchronized (LOCK) { return new JSONObject(record(read(),id).toString()); } }
    JSONObject importCopy(InputStream input, String name, String mime, String source, String parentId) throws Exception {
        return importCopy(input,name,mime,source,parentId,()->true);
    }
    JSONObject importCopy(InputStream input, String name, String mime, String source, String parentId, BooleanSupplier available) throws Exception {
        if (input == null) throw new IOException("无法读取选中文件");
        try (InputStream owned = input) { return importOwned(owned,name,mime,source,parentId,available); }
    }
    private JSONObject importOwned(InputStream input, String name, String mime, String source, String parentId, BooleanSupplier available) throws Exception {
        String id = WorkspaceState.id();
        synchronized (LOCK) {
            if (!available.getAsBoolean()) throw new IOException("账号或任务已改变，已停止导入");
            JSONObject ledger = read(); JSONArray records = ledger.getJSONArray("records");
            if (records.length() >= MAX_RECORDS) throw new IOException("资产库已满，请先整理回收站");
            if (parentId != null && !record(ledger,parentId).getString("status").equals("ready")) throw new IOException("原文件尚未就绪");
            String type = mime == null ? "application/octet-stream" : mime.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            if (!type.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") || type.length() > 150) type = "application/octet-stream";
            if(type.equals("application/octet-stream"))type=inferredMime(filename(name));
            JSONObject a = new JSONObject().put("id",id).put("accountId",accountId).put("name",filename(name)).put("mime",type)
                    .put("status","processing").put("size",0).put("sha256","").put("createdAt",System.currentTimeMillis())
                    .put("favorite",false).put("trashed",false).put("source","download".equals(source) ? "download" : parentId != null ? "conversion" : "import");
            if (parentId != null) a.put("parentId",parentId); records.put(a); write(ledger); ACTIVE_IMPORTS.add(directory.getPath()+":"+id);
        }
        File partial = file(id,true); long size = 0; MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try {
            try (FileChannel out = FileChannel.open(partial.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消");
                    size += n; if (size > MAX_FILE) throw new IOException("单文件上限为 256 MiB");
                    hash.update(buffer,0,n); ByteBuffer data = ByteBuffer.wrap(buffer,0,n); while (data.hasRemaining()) out.write(data);
                }
                out.force(true);
            }
            StringBuilder digest = new StringBuilder(); for (byte b : hash.digest()) digest.append(String.format(Locale.ROOT,"%02x",b & 255));
            synchronized (LOCK) {
                JSONObject ledger = read(), a = record(ledger,id);
                if (!available.getAsBoolean() || !a.getString("status").equals("processing") || a.getBoolean("trashed")) throw new IOException("资产任务已取消");
                regular(partial,MAX_FILE);
                Files.move(partial.toPath(),file(id,false).toPath(),StandardCopyOption.ATOMIC_MOVE);
                a.put("status","ready").put("size",size).put("sha256",digest.toString()); write(ledger); return new JSONObject(a.toString());
            }
        } catch (Exception error) {
            synchronized (LOCK) {
                JSONObject ledger = read(), a = record(ledger,id);
                if (a.getString("status").equals("processing")) { a.put("status","failed"); write(ledger); }
            }
            throw new IOException("导入或下载未完成，原文件未修改。单文件上限 256 MiB。", error);
        } finally { synchronized(LOCK){ACTIVE_IMPORTS.remove(directory.getPath()+":"+id);} if (partial.exists() && !Files.isSymbolicLink(partial.toPath())) Files.deleteIfExists(partial.toPath()); }
    }
    File verifiedFile(String id) throws Exception {
        JSONObject snapshot; File f;
        synchronized(LOCK){
            snapshot=new JSONObject(record(read(),id).toString());
            if(!snapshot.getString("status").equals("ready")||snapshot.getBoolean("trashed"))throw new IOException("文件尚未就绪或已在回收站");
            f=file(id,false);regular(f,MAX_FILE);
        }
        MessageDigest hash=MessageDigest.getInstance("SHA-256");long size=0;
        try(InputStream input=Files.newInputStream(f.toPath(),LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[32768];int n;
            while((n=input.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("读取已取消");
                hash.update(buffer,0,n);size+=n;
            }
        }
        StringBuilder digest=new StringBuilder();for(byte value:hash.digest())digest.append(String.format(Locale.ROOT,"%02x",value&255));
        synchronized(LOCK){
            JSONObject current=record(read(),id);regular(f,MAX_FILE);
            if(!current.getString("status").equals("ready")||current.getBoolean("trashed")||size!=snapshot.getLong("size")
                    ||!digest.toString().equals(snapshot.getString("sha256"))||!current.getString("sha256").equals(snapshot.getString("sha256")))throw new IOException("文件已改变，已停止预览或导出");
            return f;
        }
    }
    void rename(String id, String name) throws Exception { synchronized (LOCK) { JSONObject l=read();record(l,id).put("name",filename(name));write(l); } }
    void favorite(String id, boolean value) throws Exception { synchronized (LOCK) { JSONObject l=read();record(l,id).put("favorite",value);write(l); } }
    void trash(String id, boolean value) throws Exception { synchronized (LOCK) { JSONObject l=read();record(l,id).put("trashed",value);write(l); } }
    void remove(String id) throws Exception {
        synchronized (LOCK) {
            JSONObject l=read(), a=record(l,id);
            if (!a.getBoolean("trashed") || a.getString("status").equals("processing")) throw new IOException("请先移入回收站并等待任务结束");
            a.put("status","deleting");write(l);
            for (boolean partial : new boolean[]{true,false}) { File f=file(id,partial);if(f.exists()){regular(f,MAX_FILE);Files.delete(f.toPath());} }
            JSONArray old=l.getJSONArray("records"),next=new JSONArray();
            for(int i=0;i<old.length();i++)if(!old.getJSONObject(i).getString("id").equals(id))next.put(old.getJSONObject(i));
            l.put("records",next);write(l);
        }
    }
    interface EmptyAccountAction { void run() throws Exception; }
    static void withEmptyAccount(File filesDir,String accountId,EmptyAccountAction action) throws Exception {
        synchronized(LOCK){
            if(hasFiles(filesDir,accountId))throw new IOException("此账号还有资产，请先导出并清空该账号的回收站，再移除账号。");
            action.run();
        }
    }
    static boolean hasFiles(File filesDir, String accountId) throws Exception {
        WorkspaceState.profile(accountId); File d=new File(new File(filesDir.getCanonicalFile(),"assets"),accountId);
        if(Files.isSymbolicLink(d.toPath()))throw new IOException("资产目录不能是符号链接");
        if(!d.exists())return false;
        AssetLedger ledger=new AssetLedger(filesDir,accountId);
        String[] files=d.list((dir,name)->name.endsWith(".bin")||name.endsWith(".part"));
        if(files==null)throw new IOException("资产目录不可读");
        return !ledger.list().isEmpty()||files.length>0;
    }
}
