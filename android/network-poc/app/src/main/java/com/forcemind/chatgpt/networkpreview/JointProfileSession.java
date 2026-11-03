package com.forcemind.chatgpt.networkpreview;

import android.app.Activity;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.os.Looper;
import android.os.Message;
import android.util.AtomicFile;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.lang.ref.WeakReference;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Process-held owned experimental profiles. No formal accounts, URLs or default-profile fallback. */
public final class JointProfileSession {
    public interface Observer { void changed(); }
    public static final class ClearResult {
        public final String accountId, profileName, deletionError;
        public final long epoch;
        public final boolean profileDeleted;
        ClearResult(String accountId, String profileName, long epoch, boolean deleted, String error) {
            this.accountId = accountId; this.profileName = profileName; this.epoch = epoch;
            profileDeleted = deleted; deletionError = error;
        }
    }
    private static final class Account {
        final String id, profileName;
        final Profile profile;
        final List<Registration> resources = new ArrayList<>();
        long epoch;
        boolean cleared;
        ClearResult clearResult;
        Account(String id, String name, Profile profile) { this.id = id; profileName = name; this.profile = profile; }
    }
    private static final class Page {
        final String id, account, popupOwner;
        final WebView view;
        Page(String id, String account, String popupOwner, WebView view) {
            this.id = id; this.account = account; this.popupOwner = popupOwner; this.view = view;
        }
    }
    private final class Registration implements AutoCloseable {
        final Account account;
        final Runnable action;
        boolean active = true;
        Registration(Account account, Runnable action) { this.account = account; this.action = action; }
        @Override public void close() {
            synchronized (JointProfileSession.this) { active = false; account.resources.remove(this); }
        }
    }
    private final Context context;
    private final RouteManager routes = new RouteManager();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private WeakReference<Activity> owner = new WeakReference<>(null);
    private ViewGroup panel;
    private Observer observer;
    private String visible;
    private int popupSequence;
    private volatile boolean disposed;
    private static final String TOMBSTONES = "owned-joint-profile-tombstones.json";

    public JointProfileSession(Context context, String sessionId, String... accountIds) {
        mainThread();
        this.context = context.getApplicationContext();
        MainActivity.ensureWebViewSpace();
        if (!supported()) throw new UnsupportedOperationException("联合验收需要 MULTI_PROFILE 和 PROXY_OVERRIDE");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9-]{1,48}") || accountIds.length == 0)
            throw new IllegalArgumentException("实验会话标识无效");
        ProfileStore store = ProfileStore.getInstance();
        List<String> existing = store.getAllProfileNames();
        // Validate every identity before creating any profile. Never adopt an existing profile.
        for (String id : accountIds) {
            if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,23}") || accounts.containsKey(id))
                throw new IllegalArgumentException("实验账号标识无效或重复");
            String name = "joint-" + sessionId + "-" + id;
            if (existing.contains(name)) throw new IllegalArgumentException("实验 Profile 已存在，拒绝接管");
            accounts.put(id, new Account(id, name, null));
        }
        for (Account account : new ArrayList<>(accounts.values()))
            accounts.put(account.id, new Account(account.id, account.profileName, store.getOrCreateProfile(account.profileName)));
        routes.setStateListener(snapshot -> {
            for (Page page : pages.values()) page.view.getSettings().setBlockNetworkLoads(!snapshot.ready());
            notifyObserver();
        });
    }
    public static boolean supported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && RouteManager.supportsWebViewProxy();
    }
    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("页面操作须在主线程执行");
    }
    private synchronized Account activeAccount(String id) {
        Account account = accounts.get(id);
        if (disposed || account == null || account.cleared) throw new IllegalStateException("实验账号已失效");
        return account;
    }
    public RouteManager routes() { return routes; }
    public boolean isDisposed() { return disposed; }
    public synchronized CookieManager cookies(String id) { return activeAccount(id).profile.getCookieManager(); }
    public synchronized long epoch(String id) {
        Account account = accounts.get(id);
        if (account == null) throw new IllegalArgumentException("未知实验账号");
        return account.epoch;
    }
    public synchronized boolean isCurrent(String id, long epoch) {
        Account account = accounts.get(id);
        return !disposed && account != null && !account.cleared && account.epoch == epoch;
    }
    public synchronized AutoCloseable registerAccountClose(String id, Runnable action) {
        Account account = activeAccount(id);
        if (action == null) throw new IllegalArgumentException("缺少回收操作");
        Registration registration = new Registration(account, action);
        account.resources.add(registration);
        return registration;
    }
    public synchronized String profileName(String id) {
        Account account = accounts.get(id);
        if (account == null) throw new IllegalArgumentException("未知实验账号");
        return account.profileName;
    }
    public WebView createPage(String tabId, String account) { return createPage(tabId, account, null); }
    private WebView createPage(String tabId, String accountId, String popupOwner) {
        mainThread();
        Account account = activeAccount(accountId);
        if (tabId == null || !tabId.matches("[a-zA-Z][a-zA-Z0-9_-]{0,47}") || pages.containsKey(tabId))
            throw new IllegalArgumentException("实验页面标识无效或重复");
        Activity activity = owner.get();
        WebView view = new WebView(new MutableContextWrapper(activity == null ? context : activity));
        try { WebViewCompat.setProfile(view, account.profileName); }
        catch (RuntimeException failure) { view.destroy(); throw failure; }
        view.setBackgroundColor(android.graphics.Color.WHITE);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(true);
        view.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.getSettings().setSupportMultipleWindows(true);
        view.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        view.getSettings().setBlockNetworkLoads(!routes.snapshot().ready());
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme) && !"about:blank".equals(request.getUrl().toString());
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView web, boolean dialog, boolean gesture, Message message) {
                if (!gesture || disposed || owner.get() == null || !routes.snapshot().ready() || !isCurrent(accountId, account.epoch)
                        || !(message.obj instanceof WebView.WebViewTransport)) return false;
                String childId = "popup-" + (++popupSequence);
                WebView child = createPage(childId, accountId, tabId);
                showPage(childId);
                ((WebView.WebViewTransport) message.obj).setWebView(child);
                message.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                for (Page page : new ArrayList<>(pages.values())) if (page.view == window && page.popupOwner != null) removePage(page.id);
                notifyObserver();
            }
        });
        pages.put(tabId, new Page(tabId, accountId, popupOwner, view));
        if (visible == null) visible = tabId;
        notifyObserver();
        return view;
    }
    public WebView view(String tab) {
        mainThread();
        Page page = pages.get(tab);
        if (page == null) throw new IllegalArgumentException("实验页面不存在");
        return page.view;
    }
    public List<String> pageIds() { mainThread(); return Collections.unmodifiableList(new ArrayList<>(pages.keySet())); }
    public String accountOf(String tab) { mainThread(); Page page = pages.get(tab); return page == null ? null : page.account; }
    public String popupOwner(String tab) { mainThread(); Page page = pages.get(tab); return page == null ? null : page.popupOwner; }
    public String visiblePage() { mainThread(); return visible; }
    public void attach(Activity activity, ViewGroup container, Observer listener) {
        mainThread();
        if (disposed) throw new IllegalStateException("实验会话已关闭");
        Activity previous = owner.get();
        if (previous != null && previous != activity) detach(previous);
        owner = new WeakReference<>(activity); panel = container; observer = listener;
        for (Page page : pages.values()) ((MutableContextWrapper) page.view.getContext()).setBaseContext(activity);
        if (visible != null) showPage(visible);
        notifyObserver();
        routes.recheckCoreState();
    }
    public void detach(Activity activity) {
        mainThread();
        if (owner.get() != activity) return;
        observer = null; panel = null; owner.clear();
        for (Page page : pages.values()) {
            if (page.view.getParent() instanceof ViewGroup) ((ViewGroup) page.view.getParent()).removeView(page.view);
            ((MutableContextWrapper) page.view.getContext()).setBaseContext(context);
        }
    }
    public void showPage(String id) {
        mainThread();
        WebView view = view(id); visible = id;
        if (panel != null) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            panel.removeAllViews(); panel.addView(view, new ViewGroup.LayoutParams(-1, -1));
        }
        notifyObserver();
    }
    private void removePage(String id) {
        Page page = pages.remove(id);
        if (page == null) return;
        if (page.view.getParent() instanceof ViewGroup) ((ViewGroup) page.view.getParent()).removeView(page.view);
        page.view.stopLoading(); page.view.destroy();
        if (id.equals(visible)) visible = pages.isEmpty() ? null : pages.keySet().iterator().next();
    }
    public ClearResult clearAccount(String id) {
        mainThread();
        Account account;
        List<Registration> resources;
        synchronized (this) {
            account = accounts.get(id);
            if (account == null) throw new IllegalArgumentException("未知实验账号");
            if (account.cleared) return account.clearResult;
            account.cleared = true; account.epoch++;
            resources = new ArrayList<>(account.resources); account.resources.clear();
            for (Registration registration : resources) registration.active = false;
        }
        String failure = null;
        for (Registration resource : resources) try { resource.action.run(); } catch (RuntimeException error) { failure = "资源回收失败：" + error.getClass().getSimpleName(); }
        for (Page page : new ArrayList<>(pages.values())) if (page.account.equals(id)) removePage(page.id);
        boolean deleted = false;
        try { deleted = ProfileStore.getInstance().deleteProfile(account.profileName); }
        catch (RuntimeException error) { failure = "实验 Profile 删除未完成：" + error.getClass().getSimpleName(); }
        synchronized (this) { account.clearResult = new ClearResult(id, account.profileName, account.epoch, deleted, failure); }
        if (!deleted) recordDeferred(context, account, failure);
        if (panel != null && visible != null) showPage(visible);
        notifyObserver();
        return account.clearResult;
    }
    public synchronized ClearResult lastClearResult(String id) { Account account = accounts.get(id); return account == null ? null : account.clearResult; }
    public synchronized List<String> deferredProfiles() {
        List<String> names = new ArrayList<>();
        for (Account account : accounts.values())
            if (account.clearResult != null && !account.clearResult.profileDeleted) names.add(account.profileName);
        return Collections.unmodifiableList(names);
    }
    private static AtomicFile tombstoneFile(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), TOMBSTONES));
    }
    private static JSONObject readTombstones(Context context) throws Exception {
        AtomicFile file = tombstoneFile(context);
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists())
            return new JSONObject().put("schema", 1).put("profiles", new JSONArray());
        byte[] bytes = file.readFully();
        if (bytes.length > 65536) throw new IllegalStateException("实验清理记录过大");
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (json.getInt("schema") != 1 || json.getJSONArray("profiles").length() > 128)
            throw new IllegalStateException("实验清理记录格式无效");
        for (int i = 0; i < json.getJSONArray("profiles").length(); i++) {
            JSONObject item = json.getJSONArray("profiles").getJSONObject(i);
            String account = item.getString("accountId"), name = item.getString("profileName");
            if (!account.matches("[a-zA-Z][a-zA-Z0-9_-]{0,23}")
                    || !name.matches("joint-[a-zA-Z0-9-]{1,48}-[a-zA-Z][a-zA-Z0-9_-]{0,23}")
                    || !name.endsWith("-" + account)) throw new IllegalStateException("实验清理归属无效");
        }
        return json;
    }
    private static void writeTombstones(Context context, JSONObject json) throws Exception {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536 || json.getJSONArray("profiles").length() > 128)
            throw new IllegalStateException("实验清理记录已达上限");
        AtomicFile file = tombstoneFile(context); FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); }
        catch (Exception failure) { if (stream != null) file.failWrite(stream); throw failure; }
    }
    private static void recordDeferred(Context context, Account account, String failure) {
        try {
            JSONObject json = readTombstones(context); JSONArray profiles = json.getJSONArray("profiles");
            for (int i = 0; i < profiles.length(); i++)
                if (account.profileName.equals(profiles.getJSONObject(i).getString("profileName"))) return;
            profiles.put(new JSONObject().put("accountId", account.id).put("profileName", account.profileName)
                    .put("epoch", account.epoch).put("deletionError", failure == null ? "not-deleted" : failure).put("deleted", false));
            writeTombstones(context, json);
        } catch (Exception error) { throw new IllegalStateException("实验清理记录保存失败", error); }
    }
    /** Cold-process entry: call before loading any Profile. Successful cleanup retains original failure records. */
    public static List<ClearResult> cleanupDeferredProfiles(Context context) {
        mainThread(); MainActivity.ensureWebViewSpace();
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) throw new UnsupportedOperationException("需要 MULTI_PROFILE");
        try {
            JSONObject json = readTombstones(context); JSONArray profiles = json.getJSONArray("profiles");
            List<ClearResult> results = new ArrayList<>();
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject item = profiles.getJSONObject(i); String failure = null;
                boolean deleted = item.optBoolean("deleted", false);
                if (!deleted) {
                    try { ProfileStore.getInstance().deleteProfile(item.getString("profileName")); deleted = true; }
                    catch (RuntimeException error) { failure = error.getClass().getSimpleName(); }
                    item.put("deleted", deleted).put("cleanupError", failure == null ? JSONObject.NULL : failure);
                }
                results.add(new ClearResult(item.getString("accountId"), item.getString("profileName"), item.getLong("epoch"), deleted, failure));
            }
            writeTombstones(context, json);
            return Collections.unmodifiableList(results);
        } catch (Exception error) { throw new IllegalStateException("实验 Profile 冷清理失败，记录已保留", error); }
    }
    private void notifyObserver() { if (observer != null && owner.get() != null && !disposed) observer.changed(); }
    public void dispose() {
        mainThread();
        if (disposed) return;
        disposed = true;
        for (String account : new ArrayList<>(accounts.keySet())) clearAccount(account);
        routes.setStateListener(null); routes.close();
        observer = null; panel = null; owner.clear();
    }
}
