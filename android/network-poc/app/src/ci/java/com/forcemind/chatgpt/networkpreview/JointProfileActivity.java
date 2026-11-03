package com.forcemind.chatgpt.networkpreview;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.UUID;

/** CI-only owned identities. No fixture URL, account, or this Activity is exposed by preview. */
public final class JointProfileActivity extends Activity {
    private static JointProfileSession retained;
    private JointProfileSession session;
    private TextView status;
    private LinearLayout tabs;
    private FrameLayout panel;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(Color.WHITE);
        status = new TextView(this); status.setTextColor(Color.DKGRAY); status.setTextSize(13);
        status.setPadding(16, 12, 16, 12); layout.addView(status);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(tabs); layout.addView(scroll);
        panel = new FrameLayout(this); layout.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        try {
            if (retained == null || retained.isDisposed()) {
                retained = new JointProfileSession(getApplicationContext(), UUID.randomUUID().toString(), "A", "B", "C");
                retained.createPage("A1", "A"); retained.createPage("A2", "A");
                retained.createPage("B1", "B"); retained.createPage("C1", "C");
            }
            session = retained; session.attach(this, panel, this::render);
            if (session.routes().snapshot().generation == 0)
                session.routes().useSystem((success, message) -> { if (!success) status.setText(message); else render(); });
        } catch (RuntimeException failure) {
            status.setText("联合验收不可用：需要独立 Profile 与应用代理能力；" + failure.getClass().getSimpleName());
        }
    }
    private void render() {
        if (session == null || isDestroyed() || isFinishing()) return;
        RouteManager.Snapshot route = session.routes().snapshot();
        String text;
        switch (route.state) {
            case SYSTEM: text = "使用系统网络"; break;
            case CONNECTED: text = "已连接 · 应用线路"; break;
            case FAILED: text = "连接失败 · 请手动重连"; break;
            case CONNECTING: text = "正在连接"; break;
            case DIRECT: text = "直连"; break;
            default: text = "实验已关闭";
        }
        status.setText(text + " · 页面与账号独立");
        tabs.removeAllViews();
        for (String id : session.pageIds()) {
            Button button = new Button(this); button.setText(id);
            button.setContentDescription("联合页面 " + id);
            button.setOnClickListener(v -> session.showPage(id)); tabs.addView(button);
        }
    }
    public JointProfileSession sessionForTests() {
        if (session == null) throw new IllegalStateException("联合会话不可用");
        return session;
    }
    public void showPageForTests(String id) { sessionForTests().showPage(id); }
    public String statusForTests() { return status.getText().toString(); }
    @Override protected void onResume() { super.onResume(); if (session != null) session.routes().recheckCoreState(); }
    @Override protected void onDestroy() { if (session != null) session.detach(this); super.onDestroy(); }
}
