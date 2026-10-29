package com.forcemind.chatgpt.networkpreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated proof-of-concept application; its app ID and WebView directory never share formal login data. */
public final class MainActivity extends Activity {
    private static final AtomicBoolean SPACE_SET = new AtomicBoolean();
    private WebView web;
    private RouteManager routes;
    private NativeDownloads downloads;
    private NodeStore store;
    private NodeStore.Data data;
    private TextView status;
    private boolean storeReadable = true, initialNavigation = true;
    private ValueCallback<Uri[]> fileCallback;
    private final java.util.List<WebView> popups = new java.util.ArrayList<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (SPACE_SET.compareAndSet(false, true)) WebView.setDataDirectorySuffix("network-preview");
        routes = new RouteManager(); downloads = new NativeDownloads(this, routes); store = new NodeStore(this);
        try { data = store.read(); } catch (Exception e) { data = new NodeStore.Data(); storeReadable = false; }
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(16), 0, dp(8), 0);
        status = new TextView(this); status.setTextColor(Color.DKGRAY); status.setTextSize(12); status.setText("正在连接");
        bar.addView(status, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button menu = new Button(this); menu.setText("⋯"); menu.setContentDescription("网络与页面菜单"); menu.setOnClickListener(v -> showMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(56), dp(48))); layout.addView(bar);
        web = createWebView(); layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
        if (!storeReadable) message("线路存储无法解密，原文件已保留。本次只能使用直连。");
        VlessNode selected = data.selected >= 0 ? data.nodes.get(data.selected) : null;
        changeRoute(selected, data.selected);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private WebView createWebView() {
        WebView view = new WebView(this); view.setBackgroundColor(Color.WHITE);
        view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false); view.getSettings().setAllowContentAccess(true);
        view.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.getSettings().setSupportMultipleWindows(true); view.getSettings().setBlockNetworkLoads(true);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme(); return !"https".equals(scheme) && !"http".equals(scheme);
            }
            @Override public void onReceivedError(WebView w, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) status.setText("页面连接失败 · 未自动重试");
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), 41); } catch (Exception e) { fileCallback = null; callback.onReceiveValue(null); }
                return true;
            }
            @Override public boolean onCreateWindow(WebView w, boolean dialog, boolean gesture, Message result) {
                if (!gesture || routes.snapshot().state == RouteManager.State.CONNECTING || routes.snapshot().state == RouteManager.State.FAILED) return false;
                WebView popup = createWebView(); popup.getSettings().setBlockNetworkLoads(false); popups.add(popup);
                AlertDialog window = new AlertDialog.Builder(MainActivity.this).setView(popup).setNegativeButton("关闭", null).create();
                window.setOnDismissListener(d -> { popups.remove(popup); popup.destroy(); }); window.show();
                ((WebView.WebViewTransport) result.obj).setWebView(popup); result.sendToTarget(); return true;
            }
        });
        view.setDownloadListener((url, agent, disposition, mime, length) -> downloads.download(url, agent,
                (file, error) -> message(error == null ? "文件已保存到本应用专用空间：" + file.getName() : error)));
        return view;
    }
    private void showMenu() {
        new AlertDialog.Builder(this).setTitle("网络")
                .setItems(new String[]{"线路列表", "添加 VLESS 线路", "使用直连", "连接状态", "检查当前目标", "取消文件下载", "重新打开首页"}, (d, which) -> {
                    if (which == 0) showNodes();
                    else if (which == 1) editNode(-1);
                    else if (which == 2) confirmRoute(null, -1);
                    else if (which == 3) showStatus();
                    else if (which == 4) probe();
                    else if (which == 5) downloads.cancel();
                    else if (which == 6) new AlertDialog.Builder(this).setMessage("重新打开会离开当前页面。未完成的内容不会自动重发。").setNegativeButton("取消", null).setPositiveButton("打开", (a, b) -> {
                        RouteManager.State s = routes.snapshot().state;
                        if (s == RouteManager.State.DIRECT || s == RouteManager.State.CONNECTED) web.loadUrl(BuildConfig.NETWORK_HOME_URL);
                        else message("请先连接线路或选择直连");
                    }).show();
                }).show();
    }
    private void showStatus() {
        RouteManager.Snapshot s = routes.snapshot();
        String state = stateText(s.state);
        String text = "状态：" + state + "\n线路：" + (s.node == null ? "直连" : s.node.name) + "\nWebView 代理支持：" + RouteManager.supportsWebViewProxy()
                + "\n文件传输：" + routes.activeTransfers() + "\n切换不刷新页面，不自动重发消息或上传。已有连接可能需要手动重新打开。";
        new AlertDialog.Builder(this).setTitle("连接状态").setMessage(text).setPositiveButton("关闭", null)
                .setNeutralButton("详细信息", (d, w) -> message("核心：" + studio.networkcore.Networkcore.version() + "\n" + routes.error())).show();
    }
    private static String stateText(RouteManager.State state) {
        switch (state) { case DIRECT: return "直连"; case CONNECTED: return "已连接 · 本地代理"; case CONNECTING: return "正在连接"; case FAILED: return "连接失败"; default: return "未连接"; }
    }
    private void showNodes() {
        if (data.nodes.isEmpty()) { message("暂无线路，请添加 VLESS 地址。"); return; }
        String[] labels = new String[data.nodes.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (data.selected == i ? "✓ " : "") + data.nodes.get(i).name + "  •••:" + data.nodes.get(i).port;
        new AlertDialog.Builder(this).setTitle("线路列表").setItems(labels, (d, index) -> new AlertDialog.Builder(this).setTitle(data.nodes.get(index).name)
                .setItems(new String[]{"使用这条线路", "编辑", "删除"}, (a, choice) -> {
                    if (choice == 0) confirmRoute(data.nodes.get(index), index);
                    else if (choice == 1) editNode(index);
                    else deleteNode(index);
                }).show()).setNegativeButton("关闭", null).show();
    }
    private boolean mayEdit(int index) {
        if (!storeReadable) { message("加密存储不可读，未覆盖原文件"); return false; }
        if (index >= 0 && (index == data.selected || routes.snapshot().node == data.nodes.get(index))) { message("请先切换直连，再编辑或删除当前线路"); return false; }
        return true;
    }
    private void editNode(int index) {
        if (!mayEdit(index)) return;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText name = new EditText(this); name.setHint("线路名称"); name.setSingleLine(true);
        EditText link = new EditText(this); link.setHint("vless://…"); link.setSingleLine(true); link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (index >= 0) { name.setText(data.nodes.get(index).name); link.setText(data.nodes.get(index).link); }
        form.addView(name); form.addView(link);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "添加线路" : "编辑线路").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                VlessNode node = VlessNode.parse(link.getText().toString(), name.getText().toString());
                VlessNode previous = index >= 0 ? data.nodes.set(index, node) : null;
                if (index < 0) data.nodes.add(node);
                try { store.write(data); } catch (Exception e) { if (index < 0) data.nodes.remove(data.nodes.size() - 1); else data.nodes.set(index, previous); throw e; }
                dialog.dismiss();
            } catch (IllegalArgumentException e) { link.setError(e.getMessage()); }
            catch (Exception e) { message("保存失败，未改动原线路文件"); }
        })); dialog.show();
    }
    private void deleteNode(int index) {
        if (!mayEdit(index)) return;
        new AlertDialog.Builder(this).setMessage("删除线路“" + data.nodes.get(index).name + "”？").setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
            VlessNode previous = data.nodes.remove(index); int oldSelected = data.selected;
            if (data.selected > index) data.selected--;
            try { store.write(data); } catch (Exception e) { data.nodes.add(index, previous); data.selected = oldSelected; message("删除失败，原线路文件已保留"); }
        }).show();
    }
    private void confirmRoute(VlessNode node, int index) {
        if (initialNavigation) { changeRoute(node, index); return; }
        new AlertDialog.Builder(this).setTitle("切换线路")
                .setMessage("切换可能中断现有网页连接、消息流或上传。请先完成任务。当前页面保留，不自动重发。应用无法判断全部网页任务是否已完成。")
                .setNegativeButton("取消", null).setPositiveButton("切换", (dialog, which) -> changeRoute(node, index)).show();
    }
    public void changeRoute(VlessNode node, int index) {
        RouteManager.Snapshot before = routes.snapshot();
        if (routes.activeTransfers() > 0 || (before.state == RouteManager.State.CONNECTING && before.generation != 0)) { message("正在切换或传输文件，请稍后再试"); return; }
        if (node != null && !RouteManager.supportsWebViewProxy()) { message("当前 WebView 不支持应用内代理，请更新 Android System WebView"); return; }
        web.getSettings().setBlockNetworkLoads(true); for (WebView popup : popups) popup.getSettings().setBlockNetworkLoads(true);
        status.setText("正在连接");
        routes.switchTo(node, (success, detail) -> {
            status.setText(stateText(routes.snapshot().state));
            if (success) {
                data.selected = index;
                if (storeReadable) try { store.write(data); } catch (Exception e) { message("线路已应用，但选择未能保存"); }
                web.getSettings().setBlockNetworkLoads(false); for (WebView popup : popups) popup.getSettings().setBlockNetworkLoads(false);
                if (initialNavigation) { initialNavigation = false; web.loadUrl(BuildConfig.NETWORK_HOME_URL); }
            } else message(detail);
        });
    }
    private void probe() {
        status.setText("正在检查目标");
        new Thread(() -> {
            String text;
            try { NativeDownloads.Response response = downloads.request(BuildConfig.NETWORK_PROBE_URL, "GET", null); text = "检查目标返回 HTTP " + response.status + "；不代表全部业务可用"; }
            catch (Exception e) { text = "目标检查失败；未改为直连，未自动重试"; }
            String result = text; runOnUiThread(() -> { status.setText(stateText(routes.snapshot().state)); message(result + "\n" + BuildConfig.NETWORK_PROBE_URL); });
        }, "network-probe").start();
    }
    private void message(String text) { if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setMessage(text).setPositiveButton("关闭", null).show(); }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == 41 && fileCallback != null) { fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, intent)); fileCallback = null; }
    }
    public RouteManager routeManagerForTests() { return routes; }
    public WebView webViewForTests() { return web; }
    public NativeDownloads downloadsForTests() { return downloads; }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        downloads.close(); routes.close(); for (WebView popup : popups) popup.destroy(); popups.clear(); web.destroy(); super.onDestroy();
    }
}
