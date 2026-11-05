package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Event-driven controls keep the same sheet open across save, switch and failure transitions. */
final class NetworkSheet {
    static StudioSheet show(Activity activity, NetworkSession session) {
        return show(activity, session, null);
    }
    static StudioSheet show(Activity activity, NetworkSession session, Runnable dismissed) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        Panel panel = new Panel(activity, session, dismissed);
        panel.sheet.show();
        return panel.sheet;
    }
    private static final class Panel implements Application.ActivityLifecycleCallbacks {
        final Activity activity;
        final NetworkSession session;
        final StudioSheet sheet;
        final Runnable refresh = this::render;
        AlertDialog editor;
        AlertDialog prompt;
        String message = "";
        boolean registered;
        Panel(Activity activity, NetworkSession session, Runnable dismissed) {
            this.activity = activity; this.session = session;
            sheet = new StudioSheet(activity, "网络", session.status() + " · 所有账号共用");
            sheet.setOnShowListener(dialog -> {
                registered = true;
                activity.getApplication().registerActivityLifecycleCallbacks(this);
                session.observePanel(refresh);
                if (session.closed()) sheet.dismiss();
            });
            sheet.setOnDismissListener(dialog -> {
                stop();
                if (dismissed != null && !activity.isDestroyed() && !activity.isFinishing()) dismissed.run();
            });
        }
        void stop() {
            session.removePanelObserver(refresh);
            if (registered) activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            registered = false;
            if (editor != null) { editor.dismiss(); editor = null; }
            if (prompt != null) { prompt.dismiss(); prompt = null; }
        }
        void render() {
            if (!sheet.isShowing()) return;
            if (session.closed() || activity.isDestroyed()) { sheet.dismiss(); return; }
            int scroll = sheet.body.getParent() instanceof ScrollView ? ((ScrollView) sheet.body.getParent()).getScrollY() : 0;
            sheet.subtitle(session.status() + " · 所有账号共用");
            sheet.body.removeAllViews(); sheet.footer.removeAllViews();
            boolean busy = session.controlsBusy();
            if (session.unreadable()) note("原线路配置已保留，可仅本次使用系统网络。");
            else if (session.busy() || session.routes.activeTransfers() != 0 || HandoffRuntime.networkBusy())
                note("文件任务进行中，完成或取消后可切换线路。");
            if (session.routes.snapshot().state == NetworkRoute.State.FAILED) {
                note(session.routes.error());
                if (!session.unreadable()) {
                    LinearLayout retry = sheet.row("重新连接", "保留当前网页与草稿", "", () -> run(() -> session.retry(result())));
                    retry.setEnabled(!busy);
                }
            } else if (!message.isEmpty()) note(message);
            LinearLayout system = sheet.row("使用系统网络", session.unreadable() ? "仅本次，保留原线路配置"
                    : !session.enabled() && session.ready() ? "当前使用" : "停用应用内线路", "",
                    () -> confirm(() -> run(() -> session.useSystem(result()))));
            system.setEnabled(!busy);
            List<NetworkNode> nodes = session.nodes();
            for (int i = 0; i < nodes.size(); i++) {
                int index = i; NetworkNode node = nodes.get(i);
                String state = session.connectedTo(i) ? "已连接" : session.enabled() && session.selected() == i
                        ? session.changing() ? "正在连接" : "已选择 · 未连接" : "点按连接";
                String detail = state + " · " + ("none".equals(node.security) ? "RAW" : node.security.toUpperCase(java.util.Locale.ROOT)) + " · 长按编辑";
                LinearLayout row = sheet.row(node.name, detail, "", () -> confirm(() -> {
                    if (!unchanged(index, node)) { message = "线路已变更，请重新选择"; render(); return; }
                    run(() -> session.select(index, result()));
                }));
                row.setEnabled(!busy);
                row.setOnLongClickListener(view -> { if (!session.controlsBusy() && !session.unreadable()) edit(index, node); return true; });
            }
            sheet.action("添加 VLESS 线路", true, () -> edit(-1, null));
            sheet.footer.getChildAt(0).setEnabled(!busy && !session.unreadable());
            if (sheet.body.getParent() instanceof ScrollView) {
                ScrollView parent = (ScrollView) sheet.body.getParent();
                parent.post(() -> { if (sheet.isShowing()) parent.scrollTo(0, scroll); });
            }
        }
        void note(String text) {
            if (text == null || text.isEmpty()) return;
            TextView view = sheet.text(text, 13); StudioTypography.meta(view);
            view.setPadding(0, StudioTypography.dp(activity, 8), 0, StudioTypography.dp(activity, 8));
            sheet.body.addView(view);
        }
        void run(Runnable action) { message = ""; action.run(); }
        NetworkRoute.Listener result() {
            return (ok, text) -> { if (!activity.isDestroyed()) { message = text; render(); } };
        }
        void confirm(Runnable action) {
            showPrompt(new AlertDialog.Builder(activity).setTitle("更改网络？")
                    .setMessage("正在生成或上传的网页请求可能中断。标签页和草稿保留，不会自动刷新或重新发送。")
                    .setNegativeButton("取消", null).setPositiveButton("继续", (d, w) -> action.run()).create());
        }
        void showPrompt(AlertDialog dialog) {
            prompt = dialog;
            dialog.setOnDismissListener(ignored -> { if (prompt == dialog) prompt = null; });
            dialog.show();
        }
        boolean unchanged(int index, NetworkNode expected) {
            List<NetworkNode> current = session.nodes();
            return index < 0 || index < current.size() && current.get(index) == expected;
        }
        void edit(int index, NetworkNode node) {
            if (session.controlsBusy() || session.unreadable()) return;
            LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL);
            int pad = StudioTypography.dp(activity, 20); fields.setPadding(pad, pad / 2, pad, 0);
            EditText name = new EditText(activity); StudioTypography.text(name, 15); name.setHint("线路名称"); name.setContentDescription("线路名称");
            name.setSingleLine(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
            if (node != null) name.setText(node.name); fields.addView(name);
            EditText link = new EditText(activity); StudioTypography.text(link, 14); link.setHint("vless:// 地址"); link.setContentDescription("VLESS 地址");
            link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            link.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16384)});
            if (node != null) link.setText(node.link); fields.addView(link);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(index < 0 ? "添加线路" : "编辑线路")
                    .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null);
            if (index >= 0) builder.setNeutralButton("删除", (dialog, which) -> showPrompt(new AlertDialog.Builder(activity).setTitle("删除此线路？")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                        if (!unchanged(index, node)) { message = "线路已变更，请重新打开后编辑"; render(); return; }
                        run(() -> session.removeNode(index, result()));
                    }).create()));
            AlertDialog dialog = builder.create(); editor = dialog;
            dialog.setOnDismissListener(ignored -> { if (editor == dialog) editor = null; });
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!unchanged(index, node)) { name.setError("线路已变更，请重新打开后编辑"); return; }
                try {
                    NetworkNode value = NetworkNode.parse(link.getText().toString(), name.getText().toString());
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    message = "";
                    session.saveNode(index, value, (ok, text) -> {
                        if (activity.isDestroyed() || !dialog.isShowing()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (ok) { dialog.dismiss(); message = text; render(); } else name.setError(text);
                    });
                } catch (IllegalArgumentException bad) { link.setError(bad.getMessage()); }
            }));
            dialog.show();
        }
        @Override public void onActivityDestroyed(Activity target) { if (target == activity) { sheet.dismiss(); stop(); } }
        @Override public void onActivityCreated(Activity target, Bundle state) {}
        @Override public void onActivityStarted(Activity target) {}
        @Override public void onActivityResumed(Activity target) {}
        @Override public void onActivityPaused(Activity target) {}
        @Override public void onActivityStopped(Activity target) {}
        @Override public void onActivitySaveInstanceState(Activity target, Bundle state) {}
    }
    private NetworkSheet() {}
}
