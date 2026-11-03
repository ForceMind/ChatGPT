package com.forcemind.chatgpt.profiles;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.List;

/** Network controls live in More; the webpage shell stays unchanged. */
final class NetworkSheet {
    static void show(MainActivity activity, NetworkSession session) {
        StudioSheet sheet = new StudioSheet(activity, "网络", session.status()+" · 所有账号共用");
        sheet.row("使用系统网络", session.unreadable() ? "仅本次，保留原线路配置" : "停用应用内线路", "", () -> confirm(activity, () -> { sheet.dismiss(); session.useSystem(result(activity)); }));
        List<NetworkNode> nodes = session.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            int index = i; NetworkNode node = nodes.get(i);
            String detail = session.enabled() && session.selected() == i ? "当前选择" : "点按连接；长按编辑";
            LinearLayout row = sheet.row(node.name, detail, "", () -> confirm(activity, () -> { sheet.dismiss(); session.select(index, result(activity)); }));
            row.setOnLongClickListener(view -> { sheet.dismiss(); edit(activity, session, index, node); return true; });
        }
        if (session.routes.snapshot().state == NetworkRoute.State.FAILED)
            sheet.row("重新连接", session.routes.error(), "", () -> { sheet.dismiss(); session.retry(result(activity)); });
        sheet.action("添加 VLESS 线路", true, () -> { sheet.dismiss(); edit(activity, session, -1, null); });
        sheet.show();
    }
    private static NetworkRoute.Listener result(MainActivity activity) { return (ok, text) -> { if (!activity.isDestroyed()) Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }; }
    private static void confirm(MainActivity activity, Runnable action) {
        new AlertDialog.Builder(activity).setTitle("更改网络？")
                .setMessage("正在生成或上传的网页请求可能中断。标签页和草稿保留，不会自动刷新或重新发送。")
                .setNegativeButton("取消", null).setPositiveButton("继续", (d, w) -> action.run()).show();
    }
    private static void edit(MainActivity activity, NetworkSession session, int index, NetworkNode node) {
        LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL); int pad = StudioTypography.dp(activity, 20); fields.setPadding(pad, pad / 2, pad, 0);
        EditText name = new EditText(activity); StudioTypography.text(name, 15); name.setHint("线路名称"); name.setSingleLine(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)}); if (node != null) name.setText(node.name); fields.addView(name);
        EditText link = new EditText(activity); StudioTypography.text(link, 14); link.setHint("vless:// 地址"); link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); link.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16384)}); if (node != null) link.setText(node.link); fields.addView(link);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(index < 0 ? "添加线路" : "编辑线路").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (index >= 0) builder.setNeutralButton("删除", (dialog, which) -> new AlertDialog.Builder(activity).setTitle("删除此线路？").setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> session.removeNode(index, result(activity))).show());
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                NetworkNode value = NetworkNode.parse(link.getText().toString(), name.getText().toString());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                session.saveNode(index, value, (ok, message) -> { if (activity.isDestroyed()) return; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); if (ok) { dialog.dismiss(); show(activity, session); } else name.setError(message); });
            } catch (IllegalArgumentException bad) { link.setError(bad.getMessage()); }
        }));
        dialog.show();
    }
    private NetworkSheet() {}
}
