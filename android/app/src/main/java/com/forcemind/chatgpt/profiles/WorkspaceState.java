package com.forcemind.chatgpt.profiles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class WorkspaceState {
    public static final int MAX_ACCOUNTS = 30, MAX_TABS = 30;
    public static final String[] COLORS = {"#797979", "#688BAD", "#8A7AAA", "#B58A63", "#799A89", "#AB788B"};
    public final List<Account> accounts = new ArrayList<>();
    public final List<String> pendingDeletes = new ArrayList<>();
    public String activeAccount;
    public static String id() { return UUID.randomUUID().toString(); }
    public static String profile(String id) { if (!validId(id)) throw new IllegalArgumentException("Invalid profile ID"); return "profile-" + id; }
    private static boolean validId(String s) { return s != null && s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    public static final class Tab {
        public String id = WorkspaceState.id(), url = NavigationPolicy.HOME, title = "ChatGPT";
        public boolean pinned;
        JSONObject json() throws JSONException { return new JSONObject().put("id", id).put("url", url).put("title", title).put("pinned", pinned); }
    }
    public static final class Account {
        public String id = WorkspaceState.id(), storageId = WorkspaceState.id(), name = "个人账号", email = "", color = COLORS[0], activeTab;
        public final List<Tab> tabs = new ArrayList<>();
        public final List<Tab> closedTabs = new ArrayList<>();
        public Account() { Tab t = new Tab(); tabs.add(t); activeTab = t.id; }
        public Tab current() { return tabs.stream().filter(t -> t.id.equals(activeTab)).findFirst().orElseThrow(); }
        JSONObject json() throws JSONException {
            JSONArray ts = new JSONArray(), cs = new JSONArray();
            for (Tab t : tabs) ts.put(t.json()); for (Tab t : closedTabs) cs.put(t.json());
            return new JSONObject().put("id", id).put("storageId", storageId).put("name", name).put("email", email)
                    .put("color", color).put("activeTab", activeTab).put("tabs", ts).put("closedTabs", cs);
        }
    }
    public static WorkspaceState fresh() {
        WorkspaceState s = new WorkspaceState(); Account a = new Account(); s.accounts.add(a); s.activeAccount = a.id; return s;
    }
    public Account current() { return accounts.stream().filter(a -> a.id.equals(activeAccount)).findFirst().orElseThrow(); }
    public Account find(String accountId) { return accounts.stream().filter(a -> a.id.equals(accountId)).findFirst().orElseThrow(); }
    public Account addAccount(String name, String email, String color) {
        if (accounts.size() >= MAX_ACCOUNTS) throw new IllegalStateException("最多 30 个账号空间");
        Account a = new Account(); a.name = label(name, 40); a.email = label(email, 120); a.color = color; accounts.add(a); activeAccount = a.id; return a;
    }
    public Tab addTab(Account a) {
        if (a.tabs.size() >= MAX_TABS) throw new IllegalStateException("每个账号最多 30 个标签页");
        Tab t = new Tab(); a.tabs.add(t); a.activeTab = t.id; return t;
    }
    public void closeTab(Account a, String tabId) {
        Tab t = a.tabs.stream().filter(x -> x.id.equals(tabId)).findFirst().orElseThrow();
        a.closedTabs.add(t); if (a.closedTabs.size() > 10) a.closedTabs.remove(0);
        int index = a.tabs.indexOf(t); a.tabs.remove(t);
        if (a.tabs.isEmpty()) { Tab fresh = new Tab(); a.tabs.add(fresh); }
        if (a.activeTab.equals(tabId)) a.activeTab = a.tabs.get(Math.min(index, a.tabs.size() - 1)).id;
    }
    public void clearAccount(Account a) {
        pendingDeletes.add(a.storageId); a.storageId = id(); a.tabs.clear(); a.closedTabs.clear();
        Tab t = new Tab(); a.tabs.add(t); a.activeTab = t.id;
    }
    public void removeAccount(Account a) {
        if (accounts.size() == 1) throw new IllegalStateException("请至少保留一个账号空间");
        pendingDeletes.add(a.storageId); accounts.remove(a); if (activeAccount.equals(a.id)) activeAccount = accounts.get(0).id;
    }
    public WorkspaceState copy() { try { return parse(json().toString()); } catch (JSONException e) { throw new IllegalStateException(e); } }
    public JSONObject json() throws JSONException {
        validate(); JSONArray aa = new JSONArray(), dd = new JSONArray();
        for (Account a : accounts) aa.put(a.json()); for (String d : pendingDeletes) dd.put(d);
        return new JSONObject().put("schema", 1).put("activeAccount", activeAccount).put("accounts", aa).put("pendingDeletes", dd);
    }
    public static WorkspaceState parse(String raw) throws JSONException {
        JSONObject j = new JSONObject(raw); if (j.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported state version");
        WorkspaceState s = new WorkspaceState(); s.activeAccount = j.getString("activeAccount");
        JSONArray aa = j.getJSONArray("accounts");
        if (aa.length() > MAX_ACCOUNTS) throw new IllegalArgumentException("Too many accounts");
        for (int i = 0; i < aa.length(); i++) {
            JSONObject v = aa.getJSONObject(i); Account a = new Account(); a.tabs.clear();
            a.id = v.getString("id"); a.storageId = v.getString("storageId"); a.name = v.getString("name");
            a.email = v.getString("email"); a.color = v.getString("color"); a.activeTab = v.getString("activeTab");
            readTabs(v.getJSONArray("tabs"), a.tabs, MAX_TABS); readTabs(v.getJSONArray("closedTabs"), a.closedTabs, 10); s.accounts.add(a);
        }
        JSONArray dd = j.getJSONArray("pendingDeletes"); if (dd.length() > 1000) throw new IllegalArgumentException("Too many pending profiles");
        for (int i = 0; i < dd.length(); i++) s.pendingDeletes.add(dd.getString(i));
        s.validate(); return s;
    }
    private static void readTabs(JSONArray list, List<Tab> into, int max) throws JSONException {
        if (list.length() > max) throw new IllegalArgumentException("Too many tabs");
        for (int i = 0; i < list.length(); i++) {
            JSONObject j = list.getJSONObject(i); Tab t = new Tab(); t.id = j.getString("id"); t.url = j.getString("url");
            t.title = j.getString("title"); t.pinned = j.getBoolean("pinned"); into.add(t);
        }
    }
    public void validate() {
        if (accounts.isEmpty() || accounts.size() > MAX_ACCOUNTS) throw new IllegalArgumentException("Invalid account count");
        Set<String> ids = new HashSet<>(), stores = new HashSet<>();
        for (Account a : accounts) {
            if (!validId(a.id) || !ids.add(a.id) || !validId(a.storageId) || !stores.add(a.storageId)
                    || a.name.isBlank() || a.name.length() > 40 || a.email.length() > 120 || !List.of(COLORS).contains(a.color)
                    || a.tabs.isEmpty() || a.tabs.size() > MAX_TABS || a.closedTabs.size() > 10) throw new IllegalArgumentException("Invalid account");
            Set<String> ts = new HashSet<>();
            for (Tab t : a.tabs) validateTab(t, ts);
            if (!ts.contains(a.activeTab)) throw new IllegalArgumentException("Missing active tab");
            for (Tab t : a.closedTabs) validateTab(t, ts);
        }
        if (!ids.contains(activeAccount)) throw new IllegalArgumentException("Missing active account");
        Set<String> deleted = new HashSet<>();
        for (String d : pendingDeletes) if (!validId(d) || stores.contains(d) || !deleted.add(d)) throw new IllegalArgumentException("Invalid deletion queue");
    }
    private static void validateTab(Tab t, Set<String> ids) {
        if (!validId(t.id) || !ids.add(t.id) || !NavigationPolicy.chat(t.url) || t.title.length() > 120) throw new IllegalArgumentException("Invalid tab");
    }
    private static String label(String text, int max) { String s = text == null ? "" : text.trim(); if (s.length() > max) throw new IllegalArgumentException("备注过长"); return s; }
}
