package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Account selector and a fixed title-and-count entry for both browser engines. */
final class StudioBrowserHeader extends LinearLayout {
    interface Host {
        void accounts();
        void tabs();
        default void tabMenu() { tabs(); }
        void refresh();
        void stop();
        void more();
    }

    private final LinearLayout accountButton, tabButton;
    private final TextView accountName, tabTitle, tabCount;
    private final GradientDrawable accountDot = new GradientDrawable();
    private final ImageButton refresh;
    private String displayedColor = "";
    private boolean loading;

    StudioBrowserHeader(Context context, Host host) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(6), dp(3), dp(2), dp(3));
        setBackgroundColor(Color.WHITE);

        accountButton = new LinearLayout(context);
        accountButton.setGravity(Gravity.CENTER_VERTICAL);
        accountButton.setMinimumHeight(StudioTypography.controlHeight(context));
        accountButton.setPadding(dp(4), 0, dp(4), 0);
        accountButton.setFocusable(true);
        accountButton.setBackgroundResource(android.R.drawable.list_selector_background);
        accountButton.setOnClickListener(v -> host.accounts());

        View dot = new View(context);
        accountDot.setShape(GradientDrawable.OVAL);
        dot.setBackground(accountDot);
        LayoutParams dotSize = new LayoutParams(dp(6), dp(6));
        dotSize.rightMargin = dp(6);
        accountButton.addView(dot, dotSize);
        accountName = text(context, 15);
        accountName.setTypeface(null, Typeface.BOLD);
        accountName.setMaxLines(2);
        accountName.setEllipsize(TextUtils.TruncateAt.END);
        accountName.setMaxWidth(dp(96));
        accountButton.addView(accountName, new LayoutParams(-2, -2));
        ImageView arrow = new ImageView(context);
        arrow.setImageDrawable(new StudioIcon("chevron"));
        LayoutParams arrowSize = new LayoutParams(dp(12), dp(12));
        arrowSize.leftMargin = dp(4);
        accountButton.addView(arrow, arrowSize);
        addView(accountButton, new LayoutParams(-2, -2));

        tabButton = new LinearLayout(context);
        tabButton.setGravity(Gravity.CENTER_VERTICAL);
        tabButton.setMinimumHeight(StudioTypography.controlHeight(context));
        tabButton.setPadding(dp(8), dp(4), dp(8), dp(4));
        tabButton.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xfff4f4f4);
        background.setCornerRadius(dp(9));
        tabButton.setBackground(background);
        tabButton.setOnClickListener(v -> host.tabs());
        tabButton.setOnLongClickListener(v -> { host.tabMenu(); return true; });
        tabTitle = text(context, 15);
        tabTitle.setMaxLines(1);
        tabTitle.setEllipsize(TextUtils.TruncateAt.END);
        tabButton.addView(tabTitle, new LayoutParams(0, -2, 1));
        tabCount = text(context, 13);
        StudioTypography.meta(tabCount);
        LayoutParams countSize = new LayoutParams(-2, -2);
        countSize.leftMargin = dp(4);
        tabButton.addView(tabCount, countSize);
        LayoutParams tabSize = new LayoutParams(0, -2, 1);
        tabSize.leftMargin = dp(4);
        addView(tabButton, tabSize);

        refresh = icon("refresh", "刷新页面");
        refresh.setOnClickListener(v -> {
            if (loading) host.stop();
            else host.refresh();
        });
        addView(refresh, new LayoutParams(dp(48), StudioTypography.controlHeight(context)));
        ImageButton more = icon("more", "更多功能");
        more.setOnClickListener(v -> host.more());
        addView(more, new LayoutParams(dp(48), StudioTypography.controlHeight(context)));
    }

    void render(WorkspaceState.Account owner, boolean loading, boolean pageActionsEnabled) {
        if (this.loading != loading) {
            this.loading = loading;
            refresh.setImageDrawable(new StudioIcon(loading ? "close" : "refresh"));
            refresh.setContentDescription(loading ? "停止加载" : "刷新页面");
        }
        refresh.setEnabled(pageActionsEnabled);
        refresh.setAlpha(pageActionsEnabled ? 1f : .42f);
        if (!owner.name.contentEquals(accountName.getText())) {
            accountName.setText(owner.name);
            accountButton.setContentDescription("切换账号，当前：" + owner.name);
        }
        if (!displayedColor.equals(owner.color)) {
            displayedColor = owner.color;
            accountDot.setColor(Color.parseColor(owner.color));
        }
        String title = owner.current().displayTitle();
        String count = "· " + owner.tabs.size();
        if (!title.contentEquals(tabTitle.getText()) || !count.contentEquals(tabCount.getText())) {
            tabTitle.setText(title);
            tabCount.setText(count);
            tabButton.setContentDescription("查看标签页：" + title + "，" + owner.tabs.size() + " 个");
        }
    }

    private TextView text(Context context, int size) {
        TextView view = new TextView(context);
        StudioTypography.text(view, size);
        view.setTextColor(StudioTypography.INK);
        return view;
    }

    private ImageButton icon(String icon, String description) {
        ImageButton view = new ImageButton(getContext());
        view.setImageDrawable(new StudioIcon(icon));
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(13), dp(13), dp(13), dp(13));
        view.setContentDescription(description);
        return view;
    }

    private int dp(int value) {
        return StudioTypography.dp(getContext(), value);
    }
}
