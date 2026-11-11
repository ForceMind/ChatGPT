import SwiftUI
import WebKit

@main
struct StudioApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var browser = StudioBrowser()

    var body: some Scene {
        WindowGroup {
            StudioRoot(browser: browser)
                .onChange(of: scenePhase) { _, phase in
                    if phase == .inactive || phase == .background { browser.save() }
                }
        }
    }
}

private struct StudioRoot: View {
    @ObservedObject var browser: StudioBrowser
    @State private var showTabs = false
    @State private var showNewAccount = false
    @State private var accountName = ""

    var body: some View {
        if browser.loadError {
            ContentUnavailableView("本机工作区暂不可用", systemImage: "exclamationmark.triangle",
                                   description: Text("重新打开应用后再试。原有恢复记录仍在。"))
        } else {
            VStack(spacing: 0) {
                header
                WebSurface(webView: browser.currentView)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .background(Color(uiColor: .systemBackground))
            .sheet(isPresented: $showTabs) { tabList }
            .alert("新建账号", isPresented: $showNewAccount) {
                TextField("账号名称", text: $accountName)
                Button("取消", role: .cancel) {}
                Button("添加") { browser.addAccount(accountName) }
            }
            .alert("ChatGPT Studio", isPresented: Binding(
                get: { browser.message != nil },
                set: { if !$0 { browser.message = nil } }
            )) {
                Button("关闭", role: .cancel) { browser.message = nil }
            } message: {
                Text(browser.message ?? "")
            }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Menu {
                ForEach(browser.workspace.accounts) { account in
                    Button {
                        browser.selectAccount(account.id)
                    } label: {
                        if account.id == browser.workspace.activeAccountID {
                            Label(account.name, systemImage: "checkmark")
                        } else {
                            Text(account.name)
                        }
                    }
                }
                Button {
                    accountName = ""
                    showNewAccount = true
                } label: {
                    Label("新建账号", systemImage: "person.badge.plus")
                }
            } label: {
                HStack(spacing: 6) {
                    Circle().fill(.secondary).frame(width: 6, height: 6)
                    Text(browser.workspace.currentAccount.name)
                        .font(.subheadline)
                        .lineLimit(1)
                }
                .frame(maxWidth: 104, minHeight: 44)
            }
            .accessibilityLabel("账号，\(browser.workspace.currentAccount.name)")
            .buttonStyle(.glass)

            Button {
                showTabs = true
            } label: {
                HStack {
                    Text(browser.workspace.currentTab.title).lineLimit(1)
                    Spacer(minLength: 6)
                    Text(String(browser.workspace.currentAccount.tabs.count))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, minHeight: 32)
            }
            .buttonStyle(.glass)
            .accessibilityHint("打开标签列表")

            Menu {
                Button("后退", systemImage: "chevron.backward") { browser.currentView?.goBack() }
                Button("前进", systemImage: "chevron.forward") { browser.currentView?.goForward() }
                Button("强制刷新", systemImage: "arrow.clockwise") { browser.currentView?.reloadFromOrigin() }
            } label: {
                Image(systemName: "ellipsis").frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel("网页操作")
            .buttonStyle(.glass)
            Button(action: browser.reload) {
                Image(systemName: "arrow.clockwise").frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.glass)
            .accessibilityLabel("刷新")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private var tabList: some View {
        NavigationStack {
            List {
                ForEach(browser.workspace.accounts) { account in
                    Section(account.name) {
                        ForEach(account.tabs) { tab in
                            Button {
                                browser.selectTab(tab.id)
                                showTabs = false
                            } label: {
                                HStack {
                                    Text(tab.title)
                                    Spacer()
                                    if browser.workspace.currentTab.id == tab.id {
                                        Image(systemName: "checkmark")
                                            .accessibilityLabel("当前标签")
                                    }
                                }
                            }
                            .swipeActions {
                                Button("关闭标签", role: .destructive) { browser.closeTab(tab.id) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("标签页")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { showTabs = false }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("新建标签", systemImage: "plus") {
                        browser.addTab()
                        showTabs = false
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct WebSurface: UIViewRepresentable {
    let webView: WKWebView?

    func makeUIView(context: Context) -> UIView { UIView() }

    func updateUIView(_ view: UIView, context: Context) {
        guard view.subviews.first !== webView else { return }
        view.subviews.forEach { $0.removeFromSuperview() }
        guard let webView else { return }
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }
}
