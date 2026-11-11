import Foundation

enum WorkspaceError: Error {
    case invalid
    case limit
}

struct StudioWorkspace: Codable, Equatable {
    static let home = URL(string: "https://chatgpt.com/")!
    static let demoHome = URL(string: "https://studio-fixture.invalid/")!
    static let maximumDraft = 12_000
    static let maximumFileBytes = 4 * 1024 * 1024
    static let maximumAccounts = 8
    static let maximumTabs = 12

    struct Checkpoint: Codable, Equatable {
        let url: URL
        let draft: String
        let scroll: Double

        var valid: Bool {
            StudioWorkspace.pageURL(url) && draft.utf8.count <= StudioWorkspace.maximumDraft &&
                scroll.isFinite && (0...100_000_000).contains(scroll)
        }
    }

    struct Tab: Identifiable, Codable, Equatable {
        let id: UUID
        var title: String
        var url: URL
        var checkpoint: Checkpoint?

        init(url: URL) {
            id = UUID()
            title = "ChatGPT"
            self.url = url
        }
    }

    struct Account: Identifiable, Codable, Equatable {
        let id: UUID
        var name: String
        var tabs: [Tab]
        var activeTabID: UUID

        init(name: String, url: URL) {
            let tab = Tab(url: url)
            id = UUID()
            self.name = name
            tabs = [tab]
            activeTabID = tab.id
        }
    }

    private let schema = 1
    private(set) var accounts: [Account]
    private(set) var activeAccountID: UUID

    init(demo: Bool) {
        let account = Account(name: "个人账号", url: demo ? Self.demoHome : Self.home)
        accounts = [account]
        activeAccountID = account.id
    }

    private enum CodingKeys: String, CodingKey {
        case schema, accounts, activeAccountID
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        guard try values.decode(Int.self, forKey: .schema) == 1 else { throw WorkspaceError.invalid }
        accounts = try values.decode([Account].self, forKey: .accounts)
        activeAccountID = try values.decode(UUID.self, forKey: .activeAccountID)
        try validate()
    }

    var currentAccount: Account { accounts.first { $0.id == activeAccountID }! }
    var currentTab: Tab { currentAccount.tabs.first { $0.id == currentAccount.activeTabID }! }

    static func pageURL(_ url: URL) -> Bool {
        url.scheme == "https" && url.user == nil && url.password == nil &&
            (url.port == nil || url.port == 443) &&
            (url.host == home.host || url.host == demoHome.host) &&
            url.absoluteString.utf8.count <= 4096
    }

    func validate() throws {
        guard (1...Self.maximumAccounts).contains(accounts.count),
              Set(accounts.map(\.id)).count == accounts.count,
              accounts.contains(where: { $0.id == activeAccountID }) else { throw WorkspaceError.invalid }
        var tabIDs = Set<UUID>()
        for account in accounts {
            guard !account.name.isEmpty, account.name.count <= 40,
                  (1...Self.maximumTabs).contains(account.tabs.count),
                  account.tabs.contains(where: { $0.id == account.activeTabID }) else { throw WorkspaceError.invalid }
            for tab in account.tabs {
                guard tabIDs.insert(tab.id).inserted, tab.title.count <= 160, Self.pageURL(tab.url),
                      tab.checkpoint.map({ $0.valid && $0.url == tab.url }) ?? true else { throw WorkspaceError.invalid }
            }
        }
    }

    static func read(from file: URL, demo: Bool) throws -> StudioWorkspace {
        guard FileManager.default.fileExists(atPath: file.path) else { return StudioWorkspace(demo: demo) }
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        guard let size = attributes[.size] as? NSNumber, size.intValue <= maximumFileBytes else {
            throw WorkspaceError.invalid
        }
        let state = try JSONDecoder().decode(StudioWorkspace.self, from: Data(contentsOf: file))
        let host = demo ? demoHome.host : home.host
        guard state.accounts.allSatisfy({ $0.tabs.allSatisfy { $0.url.host == host } }) else {
            throw WorkspaceError.invalid
        }
        return state
    }

    func write(to file: URL) throws {
        try validate()
        let bytes = try JSONEncoder().encode(self)
        guard bytes.count <= Self.maximumFileBytes else { throw WorkspaceError.limit }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try bytes.write(to: file, options: .atomic)
        #endif
    }

    mutating func addAccount(_ name: String, url: URL) throws {
        let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard accounts.count < Self.maximumAccounts, (1...40).contains(label.count) else { throw WorkspaceError.limit }
        let account = Account(name: label, url: url)
        accounts.append(account)
        activeAccountID = account.id
    }

    mutating func selectAccount(_ id: UUID) {
        if accounts.contains(where: { $0.id == id }) { activeAccountID = id }
    }

    mutating func selectTab(_ id: UUID) {
        guard let index = accounts.firstIndex(where: { $0.tabs.contains { $0.id == id } }) else { return }
        accounts[index].activeTabID = id
        activeAccountID = accounts[index].id
    }

    mutating func addTab(url: URL) throws {
        let index = accounts.firstIndex { $0.id == activeAccountID }!
        guard accounts[index].tabs.count < Self.maximumTabs else { throw WorkspaceError.limit }
        let tab = Tab(url: url)
        accounts[index].tabs.append(tab)
        accounts[index].activeTabID = tab.id
    }

    mutating func closeTab(_ id: UUID, replacementURL: URL) {
        guard let account = accounts.firstIndex(where: { $0.tabs.contains { $0.id == id } }),
              let tab = accounts[account].tabs.firstIndex(where: { $0.id == id }) else { return }
        accounts[account].tabs.remove(at: tab)
        if accounts[account].tabs.isEmpty { accounts[account].tabs.append(Tab(url: replacementURL)) }
        if accounts[account].activeTabID == id {
            accounts[account].activeTabID = accounts[account].tabs[min(tab, accounts[account].tabs.count - 1)].id
        }
    }

    mutating func updateTab(_ id: UUID, url: URL, title: String?, checkpoint: Checkpoint?) {
        guard Self.pageURL(url),
              let account = accounts.firstIndex(where: { $0.tabs.contains { $0.id == id } }),
              let tab = accounts[account].tabs.firstIndex(where: { $0.id == id }) else { return }
        var value = accounts[account].tabs[tab]
        if value.url != url { value.checkpoint = nil }
        value.url = url
        if let title, !title.isEmpty { value.title = String(title.prefix(160)) }
        if let checkpoint, checkpoint.valid && checkpoint.url == url { value.checkpoint = checkpoint }
        accounts[account].tabs[tab] = value
    }
}
