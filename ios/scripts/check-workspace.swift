import Foundation

@main
struct CheckWorkspace {
    static func main() throws {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("studio-ios-workspace-test-" + UUID().uuidString)
            .appendingPathComponent("workspace.json")
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let page = URL(string: "https://studio-fixture.invalid/c/one")!
        var state = StudioWorkspace(demo: true)
        let originalAccount = state.currentAccount.id
        let originalTab = state.currentTab.id
        state.updateTab(originalTab, url: page, title: "合成会话", checkpoint: .init(url: page, draft: "合成草稿", scroll: 410))
        try state.addAccount("另一个账号", url: StudioWorkspace.demoHome)
        let secondAccount = state.currentAccount.id
        precondition(secondAccount != originalAccount)
        precondition(state.currentTab.checkpoint == nil)
        try state.addTab(url: StudioWorkspace.demoHome)
        let secondTab = state.currentTab.id
        state.selectAccount(originalAccount)
        precondition(state.currentTab.checkpoint?.draft == "合成草稿")
        try state.write(to: file)
        let recovered = try StudioWorkspace.read(from: file, demo: true)
        precondition(recovered == state)
        precondition(recovered.currentTab.checkpoint?.scroll == 410)
        state.selectTab(secondTab)
        precondition(state.currentAccount.id == secondAccount && state.currentTab.checkpoint == nil)
        state.closeTab(secondTab, replacementURL: StudioWorkspace.demoHome)
        precondition(state.currentTab.id != secondTab)
        precondition(state.currentAccount.tabs.count == 1)
        do {
            _ = try StudioWorkspace.read(from: file, demo: false)
            fatalError("Fixture storage was accepted by the normal workspace")
        } catch WorkspaceError.invalid {}
        let corrupt = Data("{".utf8)
        try corrupt.write(to: file)
        do {
            _ = try StudioWorkspace.read(from: file, demo: true)
            fatalError("Corrupt data was accepted")
        } catch {
            let bytes = try Data(contentsOf: file)
            precondition(bytes == corrupt)
        }
        print("Workspace checks passed: account IDs, tab selection, checkpoint round trip, fixture separation, corrupt state preservation")
    }
}
