package com.forcemind.chatgpt.profiles;

import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only actual, successfully persisted HandoffStore states become evidence. No payload or launch token. */
final class EnvironmentHandoffProbe implements EnvironmentHandoffChecks.Probe {
    private static boolean installed;
    private final File root;
    private final Map<String,String> last = new HashMap<>();
    private final JSONArray events = new JSONArray();
    private int sequence;
    private EnvironmentHandoffProbe(File files) throws IOException { root = files.getCanonicalFile(); }
    static synchronized void install(File files) {
        if (!BuildConfig.ENVIRONMENT_CLIENT || !BuildConfig.ENVIRONMENT_TEST || !BuildConfig.ENVIRONMENT_NETWORK_TEST)
            throw new SecurityException("Owned handoff evidence only");
        if (installed) return;
        try { EnvironmentHandoffChecks.install(new EnvironmentHandoffProbe(files)); installed = true; }
        catch (IOException failure) { throw new IllegalStateException("Cannot install committed handoff evidence", failure); }
    }
    @Override public synchronized void saved(JSONObject data) {
        try {
            if (data.getInt("schema") != 1) throw new IOException("Unknown handoff evidence schema");
            JSONArray jobs = data.getJSONArray("jobs"); boolean changed = false;
            for (int index = 0; index < jobs.length(); index++) {
                JSONObject job = jobs.getJSONObject(index);
                String id = job.getString("id"); WorkspaceState.profile(id);
                JSONObject value = new JSONObject().put("jobId",id).put("status",job.getString("status"))
                    .put("sourceAccountId",job.getString("sourceAccountId")).put("targetAccountId",job.getString("targetAccountId"))
                    .put("promptSha256",job.getString("promptSha256"));
                if (job.has("tabId")) value.put("tabId",job.getString("tabId"));
                String signature = value.toString();
                if (signature.equals(last.get(id))) continue;
                last.put(id,signature); changed = true;
                value.put("sequence",++sequence).put("pid",Process.myPid()).put("elapsedMillis",SystemClock.elapsedRealtime());
                events.put(value);
                if (events.length() > 2048) throw new IOException("Owned handoff evidence capacity exceeded");
            }
            if (!changed) return;
            File target = new File(root,"environment-handoff-events-"+Process.myPid()+".json"), next = new File(root,target.getName()+".new");
            JSONObject snapshot = new JSONObject().put("schema",1).put("pid",Process.myPid()).put("events",events);
            try (FileOutputStream output = new FileOutputStream(next)) {
                output.write(snapshot.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            Files.move(next.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException("Cannot preserve committed handoff evidence", failure); }
    }
}
