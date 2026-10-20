package com.forcemind.chatgpt.profiles;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class MainActivity extends ComponentActivity {
    private static final int INK = Color.rgb(23, 23, 23), GRAY = Color.rgb(244, 244, 244);
    private WorkspaceState state;
    private StateStore store;
    private LinearLayout root, header, tabs;
    private HorizontalScrollView tabScroll;
    private FrameLayout content;
    private ProgressBar progress;
    private final Map<String, WebView> views = new HashMap<>();
    private final Map<String, String> popupOwners = new HashMap<>();
    private ValueCallback<Uri[]> fileCallback;
    private String fileOwner;
    private PermissionRequest mediaRequest;
    private String mediaOwner;
    private boolean ready, fatal;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        buildLayout();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        boolean supportsProfiles;
        try { supportsProfiles = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE); }
        catch (RuntimeException error) { supportsProfiles = false; }
        if (!supportsProfiles) {
            showMessage("请更新 Android System WebView", "此设备的网页组件不支持独立账号空间。请更新 Android System WebView / Chrome 后重新打开。", "打开更新页", () -> openExternal("https://play.google.com/store/apps/details?id=com.google.android.webview"));
            return;
        }
        try {
            store = new StateStore(getFilesDir()); state = store.load();
            // Delete before binding any WebView. Loaded profiles cannot be deleted in this process.
            List<String> cleared = new ArrayList<>();
            for (String id : state.pendingDeletes) {
                try { ProfileStore.getInstance().deleteProfile(WorkspaceState.profile(id)); cleared.add(id); }
                catch (IllegalStateException ignored) { /* retry at the next process start */ }
            }
            if (!cleared.isEmpty()) { WorkspaceState next = state.copy(); next.pendingDeletes.removeAll(cleared); store.save(next); state = next; }
            ready = true; showCurrent();
        } catch (Exception e) { fail("无法读取账号记录", e); }
    }

    private void buildLayout() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setBackgroundColor(GRAY);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this); tabs.setGravity(Gravity.CENTER_VERTICAL); tabScroll.addView(tabs);
        header.addView(tabScroll, new LinearLayout.LayoutParams(0, -1, 1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(INK));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2))); progress.setVisibility(View.INVISIBLE);
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
    }

    private void renderHeader() {
        tabs.removeAllViews(); while (header.getChildCount() > 1) header.removeViewAt(1);
        WorkspaceState.Account account = state.current();
        for (WorkspaceState.Tab tab : account.tabs) {
            TextView item = button((tab.pinned ? "• " : "") + tab.title, "标签页：" + tab.title);
            item.setMaxLines(1); item.setEllipsize(TextUtils.TruncateAt.END); item.setTextSize(13);
            item.setTypeface(null, tab.id.equals(account.activeTab) ? Typeface.BOLD : Typeface.NORMAL);
            item.setBackground(round(tab.id.equals(account.activeTab) ? Color.WHITE : GRAY));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(148), dp(40)); p.setMargins(dp(4), dp(4), 0, dp(4)); tabs.addView(item, p);
            item.setOnClickListener(v -> { if (change(s -> s.current().activeTab = tab.id)) showCurrent(); });
            item.setOnLongClickListener(v -> { showTabMenu(account.id, tab.id); return true; });
            if (tab.id.equals(account.activeTab)) item.post(() -> tabScroll.smoothScrollTo(Math.max(0, item.getLeft() - dp(8)), 0));
        }
        TextView add = button("+", "新建标签页"); add.setTextSize(24); header.addView(add, new LinearLayout.LayoutParams(dp(44), -1));
        add.setOnClickListener(v -> { if (change(s -> s.addTab(s.current()))) showCurrent(); });
        TextView accounts = button("● " + account.name + " ⌄", "账号与操作菜单，当前：" + account.name);
        SpannableString label = new SpannableString(accounts.getText()); label.setSpan(new ForegroundColorSpan(Color.parseColor(account.color)), 0, 1, 0); accounts.setText(label);
        accounts.setTextSize(12); accounts.setMaxLines(1); accounts.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(accounts, new LinearLayout.LayoutParams(dp(116), -1)); accounts.setOnClickListener(v -> showAccountMenu());
    }

    private void showCurrent() {
        if (!ready || fatal) return;
        cancelRequests(); renderHeader(); content.removeAllViews();
        WorkspaceState.Account account = state.current(); WorkspaceState.Tab tab = account.current();
        try {
            WebView web = views.get(tab.id);
            if (web == null) { web = makeWebView(account.id, tab.id, account.storageId); views.put(tab.id, web); web.loadUrl(tab.url); }
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            content.addView(web, new FrameLayout.LayoutParams(-1, -1)); web.onResume();
        } catch (Exception e) { fail("网页空间无法打开", e); }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView makeWebView(String accountId, String tabId, String storageId) {
        WebView web = new WebView(this);
        try { WebViewCompat.setProfile(web, WorkspaceState.profile(storageId)); }
        catch (RuntimeException e) { web.destroy(); throw e; }
        // Profile binding must be the first operation after constructing each WebView.
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true); settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSafeBrowsingEnabled(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.WHITE);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!request.isForMainFrame()) return !NavigationPolicy.https(url);
                if (NavigationPolicy.internal(url)) return false;
                if (request.isForMainFrame() && request.hasGesture() && NavigationPolicy.https(url)) askExternal(url);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!exists(accountId, tabId)) return;
                change(s -> s.find(accountId).tabs.stream().filter(t -> t.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")).url = NavigationPolicy.restorable(url));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && active(tabId)) showMessage("网页暂时无法加载", "请检查网络后重试。账号登录数据仍保留。", "重试", () -> { showCurrent(); currentWeb().loadUrl(state.current().current().url); });
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); if (active(tabId)) showMessage("连接证书校验失败", "请检查设备时间与网络，确认后重新打开。", "重试", () -> { showCurrent(); currentWeb().reload(); });
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                views.remove(tabId); if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view); view.destroy();
                if (active(tabId)) showMessage("网页进程已退出", "重新加载后可继续使用；未发送的网页草稿可能需要重新填写。", "重新加载", MainActivity.this::showCurrent);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                if (title == null || title.trim().isEmpty() || !exists(accountId, tabId)) return;
                if (change(s -> s.find(accountId).tabs.stream().filter(t -> t.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")).title = title.substring(0, Math.min(120, title.length()))) && active(tabId)) renderHeader();
            }
            @Override public void onProgressChanged(WebView view, int value) {
                if (active(tabId)) { progress.setProgress(value); progress.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE); }
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) {
                if (!gesture || !exists(accountId, tabId) || fatal) return false;
                if (!change(s -> s.addTab(s.find(accountId)))) return false;
                WorkspaceState.Account a = state.find(accountId); WorkspaceState.Tab popup = a.current();
                try {
                    WebView child = makeWebView(accountId, popup.id, a.storageId); views.put(popup.id, child); popupOwners.put(popup.id, tabId);
                    ((WebView.WebViewTransport) result.obj).setWebView(child); result.sendToTarget();
                    if (state.activeAccount.equals(accountId)) showCurrent(); return true;
                } catch (RuntimeException error) { closeTab(accountId, popup.id); toast("无法创建登录弹窗，请重试"); return false; }
            }
            @Override public void onCloseWindow(WebView window) { if (exists(accountId, tabId)) closeTab(accountId, tabId); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!active(tabId)) return false;
                cancelRequests(); fileCallback = callback; fileOwner = tabId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                String[] accepts = params.getAcceptTypes(); if (accepts.length > 0 && !accepts[0].trim().isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, accepts);
                try { startActivityForResult(intent, 10); } catch (ActivityNotFoundException e) { cancelRequests(); toast("设备没有可用的文件选择器"); }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) { runOnUiThread(() -> requestMedia(request, tabId)); }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { if (mediaRequest == request) { mediaRequest = null; mediaOwner = null; } }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
        });
        web.setDownloadListener((url, userAgent, disposition, type, size) -> {
            if (active(tabId)) new AlertDialog.Builder(this).setTitle("下载文件")
                .setMessage("将通过系统文件选择器保存此文件。")
                .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> downloads().choose(web, url, userAgent, disposition, type)).show();
        });
        return web;
    }

    private DownloadHelper downloadHelper;
    private DownloadHelper downloads() { if (downloadHelper == null) downloadHelper = new DownloadHelper(this); return downloadHelper; }
    private void showAccountMenu() {
        String[] actions = {"切换账号", "添加账号", "管理当前账号", "重新加载", "后退", "前进", "ChatGPT 首页", "恢复关闭的标签页"};
        new AlertDialog.Builder(this).setTitle(state.current().name).setItems(actions, (d, index) -> {
            WebView web = currentWeb();
            switch (index) {
                case 0 -> chooseAccount(); case 1 -> editAccount(null); case 2 -> manageAccount();
                case 3 -> { showCurrent(); currentWeb().reload(); }
                case 4 -> { if (web != null && web.canGoBack()) web.goBack(); }
                case 5 -> { if (web != null && web.canGoForward()) web.goForward(); }
                case 6 -> { showCurrent(); currentWeb().loadUrl(NavigationPolicy.HOME); }
                case 7 -> {
                    if (state.current().closedTabs.isEmpty()) { toast("没有可恢复的标签页"); return; }
                    if (change(s -> { WorkspaceState.Account a = s.current(); if (a.tabs.size() >= WorkspaceState.MAX_TABS) throw new IllegalStateException("标签页已达上限"); WorkspaceState.Tab t = a.closedTabs.remove(a.closedTabs.size() - 1); a.tabs.add(t); a.activeTab = t.id; })) showCurrent();
                }
            }
        }).show();
    }
    private void chooseAccount() {
        CharSequence[] labels = new CharSequence[state.accounts.size()];
        for (int i = 0; i < labels.length; i++) {
            WorkspaceState.Account a = state.accounts.get(i);
            SpannableString s = new SpannableString("● " + a.name + (a.id.equals(state.activeAccount) ? "  ✓" : "") + (a.email.trim().isEmpty() ? "" : "\n    " + a.email));
            s.setSpan(new ForegroundColorSpan(Color.parseColor(a.color)), 0, 1, 0); labels[i] = s;
        }
        new AlertDialog.Builder(this).setTitle("切换账号").setItems(labels, (d, i) -> { String id = state.accounts.get(i).id; if (change(s -> s.activeAccount = id)) showCurrent(); }).setNeutralButton("添加账号", (d, w) -> editAccount(null)).show();
    }
    private void editAccount(String accountId) {
        WorkspaceState.Account old = accountId == null ? null : state.find(accountId);
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = field("账号名称", old == null ? "" : old.name, 40), email = field("邮箱备注（可选）", old == null ? "" : old.email, 120);
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        fields.addView(name); fields.addView(email);
        LinearLayout colors = new LinearLayout(this); fields.addView(colors); String[] selected = {old == null ? WorkspaceState.COLORS[state.accounts.size() % WorkspaceState.COLORS.length] : old.color};
        List<TextView> dots = new ArrayList<>();
        for (String color : WorkspaceState.COLORS) {
            TextView dot = button(color.equals(selected[0]) ? "●" : "○", "账号色点 " + color); dot.setTextColor(Color.parseColor(color)); dot.setTextSize(26); colors.addView(dot, new LinearLayout.LayoutParams(0, dp(48), 1)); dots.add(dot);
            dot.setOnClickListener(v -> { selected[0] = color; for (int i = 0; i < dots.size(); i++) dots.get(i).setText(WorkspaceState.COLORS[i].equals(color) ? "●" : "○"); });
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(old == null ? "添加账号" : "编辑账号").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().trim().isEmpty()) { name.setError("请填写名称"); return; }
            if (change(s -> { if (accountId == null) s.addAccount(name.getText().toString(), email.getText().toString(), selected[0]); else { WorkspaceState.Account a = s.find(accountId); a.name = name.getText().toString().trim(); a.email = email.getText().toString().trim(); a.color = selected[0]; } })) { dialog.dismiss(); showCurrent(); }
        })); dialog.show();
    }
    private void manageAccount() {
        String id = state.activeAccount;
        new AlertDialog.Builder(this).setTitle(state.current().name).setItems(new String[]{"编辑名称与邮箱备注", "向前移动账号", "向后移动账号", "清除本机登录数据", "移除账号"}, (d, i) -> {
            if (i == 0) editAccount(id);
            else if (i == 1 || i == 2) { if (change(s -> { int index = s.accounts.indexOf(s.find(id)), target = index + (i == 1 ? -1 : 1); if (target >= 0 && target < s.accounts.size()) java.util.Collections.swap(s.accounts, index, target); })) renderHeader(); }
            else new AlertDialog.Builder(this).setTitle(i == 3 ? "清除本机登录数据？" : "移除此账号？")
                .setMessage("此账号的本机标签和登录将关闭，云端对话不受影响。旧浏览器档案在下次启动时清理，其他账号保持独立。")
                .setNegativeButton("取消", null).setPositiveButton("确认", (x, y) -> {
                    if (i == 4 && state.accounts.size() == 1) { toast("请至少保留一个账号空间"); return; }
                    List<String> ids = new ArrayList<>(); for (WorkspaceState.Tab t : state.find(id).tabs) ids.add(t.id);
                    try { store.revoke(state.find(id).storageId); } catch (Exception e) { fail("无法保护账号清除记录", e); return; }
                    cancelRequests(); for (String tabId : ids) destroyView(tabId);
                    if (change(s -> { if (i == 3) s.clearAccount(s.find(id)); else s.removeAccount(s.find(id)); })) showCurrent();
                    else fail("账号记录无法保存", new IllegalStateException("已停止使用旧空间，请重新打开应用"));
                }).show();
        }).show();
    }
    private void showTabMenu(String accountId, String tabId) {
        WorkspaceState.Tab tab = state.find(accountId).tabs.stream().filter(t -> t.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab"));
        new AlertDialog.Builder(this).setTitle(tab.title).setItems(new String[]{tab.pinned ? "取消固定" : "固定标签页", "向前移动", "向后移动", "关闭标签页"}, (d, i) -> {
            if (i == 3) { closeTab(accountId, tabId); return; }
            if (change(s -> { WorkspaceState.Account a = s.find(accountId); WorkspaceState.Tab t = a.tabs.stream().filter(v -> v.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")); if (i == 0) t.pinned = !t.pinned; else { int from = a.tabs.indexOf(t), to = from + (i == 1 ? -1 : 1); if (to >= 0 && to < a.tabs.size()) java.util.Collections.swap(a.tabs, from, to); } })) renderHeader();
        }).show();
    }
    private void closeTab(String accountId, String tabId) {
        if (change(s -> s.closeTab(s.find(accountId), tabId))) { destroyView(tabId); showCurrent(); }
    }
    private void destroyView(String tabId) {
        WebView web = views.remove(tabId); popupOwners.remove(tabId);
        if (web != null) { if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web); web.stopLoading(); web.destroy(); }
    }
    private boolean change(Consumer<WorkspaceState> mutation) {
        if (fatal) return false;
        try { WorkspaceState next = state.copy(); mutation.accept(next); store.save(next); state = next; return true; }
        catch (Exception e) { toast("操作未保存：" + e.getMessage()); return false; }
    }
    private boolean exists(String accountId, String tabId) { return !fatal && state.accounts.stream().anyMatch(a -> a.id.equals(accountId) && a.tabs.stream().anyMatch(t -> t.id.equals(tabId))); }
    private boolean active(String tabId) { return ready && !fatal && state.current().activeTab.equals(tabId); }
    private WebView currentWeb() { return ready && !fatal ? views.get(state.current().activeTab) : null; }

    private void requestMedia(PermissionRequest request, String tabId) {
        if (!active(tabId) || !NavigationPolicy.chat(request.getOrigin().toString())) { request.deny(); return; }
        List<String> needed = new ArrayList<>();
        for (String r : request.getResources()) {
            if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needed.add(Manifest.permission.RECORD_AUDIO);
            else if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needed.add(Manifest.permission.CAMERA);
            else { request.deny(); return; }
        }
        if (needed.isEmpty()) { request.deny(); return; }
        cancelRequests(); mediaRequest = request; mediaOwner = tabId;
        new AlertDialog.Builder(this).setTitle("允许网页使用麦克风 / 摄像头？").setMessage("来源：" + request.getOrigin() + "\n账号：" + state.current().name)
            .setNegativeButton("拒绝", (d, w) -> cancelRequests()).setOnCancelListener(d -> cancelRequests())
            .setPositiveButton("允许", (d, w) -> { if (mediaRequest == request && active(tabId)) requestPermissions(needed.toArray(new String[0]), 20); }).show();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 20 && mediaRequest != null) {
            PermissionRequest request = mediaRequest; boolean allowed = active(mediaOwner) && grants.length > 0;
            for (int result : grants) allowed &= result == PackageManager.PERMISSION_GRANTED;
            mediaRequest = null; mediaOwner = null; if (allowed) request.grant(request.getResources()); else request.deny();
        }
    }
    @Override protected void onActivityResult(int code, int result, Intent intent) {
        super.onActivityResult(code, result, intent);
        if (code == 10 && fileCallback != null) {
            List<Uri> uris = new ArrayList<>();
            if (result == RESULT_OK && intent != null && active(fileOwner)) {
                if (intent.getClipData() != null) for (int i = 0; i < intent.getClipData().getItemCount(); i++) uris.add(intent.getClipData().getItemAt(i).getUri());
                else if (intent.getData() != null) uris.add(intent.getData());
            }
            uris.removeIf(uri -> !"content".equals(uri.getScheme())); ValueCallback<Uri[]> callback = fileCallback; fileCallback = null; fileOwner = null; callback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        }
        if (code == DownloadHelper.REQUEST_SAVE && downloadHelper != null) downloadHelper.onResult(result, intent);
    }
    private void cancelRequests() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; fileOwner = null; }
        if (mediaRequest != null) { mediaRequest.deny(); mediaRequest = null; mediaOwner = null; }
        if (downloadHelper != null) downloadHelper.cancelSelection();
    }
    private void navigateBack() {
        WebView web = currentWeb();
        if (web != null && web.canGoBack()) web.goBack();
        else if (ready && !fatal && popupOwners.containsKey(state.current().activeTab)) closeTab(state.activeAccount, state.current().activeTab);
        else moveTaskToBack(true);
    }
    @Override protected void onResume() { super.onResume(); WebView web = currentWeb(); if (web != null) web.onResume(); }
    @Override protected void onPause() { WebView web = currentWeb(); if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onStop() {
        for (WebView web : views.values()) WebViewCompat.getProfile(web).getCookieManager().flush();
        super.onStop();
    }
    @Override protected void onDestroy() {
        cancelRequests(); if (downloadHelper != null) downloadHelper.close();
        for (String id : new ArrayList<>(views.keySet())) destroyView(id); super.onDestroy();
    }
    private void fail(String title, Exception error) {
        fatal = true; ready = false; cancelRequests(); for (String id : new ArrayList<>(views.keySet())) destroyView(id);
        header.removeAllViews(); showMessage(title, error.getMessage() == null ? "请保留应用数据并反馈。" : error.getMessage(), "关闭", this::finish);
    }
    private void showMessage(String title, String body, String action, Runnable onClick) {
        content.removeAllViews(); progress.setVisibility(View.INVISIBLE);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER); card.setPadding(dp(28), dp(24), dp(28), dp(24));
        TextView heading = text(title, 20); heading.setTypeface(null, Typeface.BOLD); card.addView(heading);
        TextView message = text(body, 14); message.setPadding(0, dp(16), 0, dp(24)); message.setTextColor(Color.DKGRAY); card.addView(message);
        TextView retry = button(action, action); retry.setBackground(round(INK)); retry.setTextColor(Color.WHITE); retry.setPadding(dp(24), 0, dp(24), 0); retry.setOnClickListener(v -> onClick.run()); card.addView(retry, new LinearLayout.LayoutParams(-2, dp(48)));
        content.addView(card, new FrameLayout.LayoutParams(-1, -1));
    }
    private void askExternal(String url) { new AlertDialog.Builder(this).setTitle("在系统浏览器中打开？").setMessage(Uri.parse(url).getHost()).setNegativeButton("取消", null).setPositiveButton("打开", (d, w) -> openExternal(url)).show(); }
    private void openExternal(String url) { if (NavigationPolicy.https(url)) try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException e) { toast("没有可用的浏览器"); } }
    private EditText field(String hint, String value, int max) { EditText v = new EditText(this); v.setTextColor(INK); v.setTextSize(15); v.setHint(hint); v.setText(value); v.setSingleLine(true); v.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)}); return v; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextColor(INK); t.setTextSize(size); t.setGravity(Gravity.CENTER); return t; }
    private TextView button(String value, String description) { TextView t = text(value, 14); t.setContentDescription(description); t.setGravity(Gravity.CENTER); t.setPadding(dp(8), 0, dp(8), 0); t.setMinHeight(dp(48)); t.setClickable(true); t.setFocusable(true); t.setBackgroundResource(android.R.drawable.list_selector_background); return t; }
    private GradientDrawable round(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(10)); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
