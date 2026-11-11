import Foundation
import WebKit
import SwiftUI

@MainActor
final class StudioBrowser: NSObject, ObservableObject, WKNavigationDelegate {
    @Published private(set) var workspace: StudioWorkspace
    @Published var message: String?
    let loadError: Bool
    private let file: URL
    private let demo: Bool
    private var browsers: [UUID: WKWebView] = [:]
    private var restoring: Set<UUID> = []
    private var writeTask: Task<Void, Never>?

    private var home: URL { demo ? StudioWorkspace.demoHome : StudioWorkspace.home }

    override init() {
        let demo = ProcessInfo.processInfo.arguments.contains("--studio-fixture")
        self.demo = demo
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("StudioPOC", isDirectory: true)
        let file = directory.appendingPathComponent(demo ? "fixture-workspace.json" : "workspace.json")
        self.file = file
        do {
            workspace = try StudioWorkspace.read(from: file, demo: demo)
            loadError = false
        } catch {
            workspace = StudioWorkspace(demo: demo)
            loadError = true
        }
        super.init()
        if !loadError { openCurrent() }
    }

    var currentView: WKWebView? { browsers[workspace.currentTab.id] }

    func selectAccount(_ id: UUID) {
        workspace.selectAccount(id)
        openCurrent()
        save()
    }

    func selectTab(_ id: UUID) {
        workspace.selectTab(id)
        openCurrent()
        save()
    }

    func addAccount(_ name: String) {
        do {
            try workspace.addAccount(name, url: home)
            openCurrent()
            save()
        } catch {
            message = "账号名称须为 1–40 字，最多添加 8 个账号。"
        }
    }

    func addTab() {
        do {
            try workspace.addTab(url: home)
            openCurrent()
            save()
        } catch {
            message = "当前账号最多打开 12 个标签。"
        }
    }

    func closeTab(_ id: UUID) {
        browsers.removeValue(forKey: id)?.stopLoading()
        restoring.remove(id)
        workspace.closeTab(id, replacementURL: home)
        openCurrent()
        save()
    }

    func reload() {
        if demo, let view = currentView { view.loadHTMLString(Self.demoPage, baseURL: home) }
        else { currentView?.reload() }
    }

    func save() {
        writeTask?.cancel()
        writeTask = nil
        guard !loadError else { return }
        do { try workspace.write(to: file) }
        catch { message = "本机恢复记录暂时无法保存。" }
    }

    private func scheduleSave() {
        writeTask?.cancel()
        writeTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            self?.save()
        }
    }

    private func openCurrent() {
        let account = workspace.currentAccount
        let tab = workspace.currentTab
        guard browsers[tab.id] == nil else { return }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = WKWebsiteDataStore(forIdentifier: account.id)
        config.userContentController.add(
            SnapshotBridge { [weak self] message in self?.capture(message, id: tab.id) },
            name: "studioCheckpoint"
        )
        config.userContentController.addUserScript(
            WKUserScript(source: Self.captureScript, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
        )
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = self
        browsers[tab.id] = web
        if demo { web.loadHTMLString(Self.demoPage, baseURL: home) }
        else { web.load(URLRequest(url: tab.url)) }
    }

    private func browserPage(_ url: URL) -> Bool {
        StudioWorkspace.pageURL(url) && url.host == home.host
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard let url = navigationAction.request.url else { return .cancel }
        if navigationAction.targetFrame?.isMainFrame == false { return .allow }
        if browserPage(url) { return .allow }
        if demo && url.absoluteString == "about:blank" { return .allow }
        if !demo, url.scheme == "https", url.user == nil, url.password == nil,
           (url.port == nil || url.port == 443),
           let host = url.host, ["auth.openai.com", "auth0.openai.com"].contains(host) { return .allow }
        message = "此链接无法在当前 iOS 验证版中打开。"
        return .cancel
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let id = browsers.first(where: { $0.value === webView })?.key,
              let tab = workspace.accounts.lazy.flatMap(\.tabs).first(where: { $0.id == id }) else { return }
        let url = demo ? home : webView.url ?? home
        guard browserPage(url) else { return }
        workspace.updateTab(id, url: url, title: webView.title, checkpoint: nil)
        scheduleSave()
        guard let checkpoint = tab.checkpoint, checkpoint.url == url else { return }
        restoring.insert(id)
        Task { [weak self, weak webView] in
            guard let self, let webView else { return }
            defer { restoring.remove(id) }
            guard browsers[id] === webView, demo || webView.url == checkpoint.url else { return }
            _ = try? await webView.callAsyncJavaScript(
                "return window.__studioRestore?.(draft, scroll, address)",
                arguments: ["draft": checkpoint.draft, "scroll": checkpoint.scroll,
                            "address": demo ? "" : checkpoint.url.absoluteString],
                in: nil, contentWorld: .page
            )
        }
    }

    private func capture(_ message: WKScriptMessage, id: UUID) {
        guard message.frameInfo.isMainFrame, let browser = browsers[id], message.webView === browser,
              !restoring.contains(id), let data = message.body as? [String: Any],
              let draft = data["draft"] as? String, let scroll = data["scroll"] as? Double else { return }
        let url: URL
        if demo { url = home }
        else {
            guard let address = data["address"] as? String, let page = URL(string: address),
                  browser.url == page, browserPage(page) else { return }
            url = page
        }
        let snapshot = StudioWorkspace.Checkpoint(url: url, draft: draft, scroll: scroll)
        guard snapshot.valid else { return }
        workspace.updateTab(id, url: url, title: browser.title, checkpoint: snapshot)
        scheduleSave()
    }

    private static let captureScript = """
    (() => {
      const field = () => document.querySelector('#prompt-textarea,textarea[data-testid="prompt-textarea"]');
      const text = node => node ? (node.value ?? node.textContent ?? '') : '';
      let timer;
      const checkpoint = () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
          window.webkit.messageHandlers.studioCheckpoint.postMessage({
            address: location.href, draft: text(field()).slice(0, 12001), scroll: window.scrollY
          });
        }, 150);
      };
      window.__studioRestore = (draft, scroll, address) => {
        if (address && location.href !== address) return;
        const node = field();
        if (node && draft && !text(node)) {
          if (node instanceof HTMLTextAreaElement) {
            Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(node, draft);
          } else if (node.isContentEditable) node.textContent = draft;
          node.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
        }
        window.scrollTo(0, scroll);
      };
      document.addEventListener('input', event => {
        const node = field();
        if (node && (event.target === node || node.contains(event.target))) checkpoint();
      }, true);
      window.addEventListener('scroll', checkpoint, { passive: true });
    })();
    """

    private static let demoPage = """
    <!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width, initial-scale=1">
    <title>草稿恢复测试</title>
    <style>
      body{margin:0 auto;max-width:44rem;padding:3rem 1.25rem;font:1rem -apple-system, sans-serif;color:CanvasText;background:Canvas}
      textarea{display:block;width:100%;box-sizing:border-box;min-height:7rem;margin-top:1rem;border-radius:1rem;padding:1rem;font:inherit}
      article{min-height:110vh}
    </style>
    <article><h1>草稿恢复测试</h1><p>输入一段合成草稿，切换账号或退出后再打开。</p>
    <label for="prompt-textarea">草稿</label><textarea id="prompt-textarea"></textarea></article>
    <p>滚动位置恢复检查</p>
    </html>
    """
}

@MainActor
private final class SnapshotBridge: NSObject, WKScriptMessageHandler {
    let receive: (WKScriptMessage) -> Void

    init(receive: @escaping (WKScriptMessage) -> Void) { self.receive = receive }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        receive(message)
    }
}
