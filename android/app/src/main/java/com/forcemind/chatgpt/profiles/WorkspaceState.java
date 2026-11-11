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
    public final List<EnvironmentGroup> environmentGroups = new ArrayList<>();
    public final List<EnvironmentDelete> pendingEnvironmentDeletes = new ArrayList<>();
    public static final class EnvironmentGroup {
        public final int slot;
        public final String language, timeZone;
        public EnvironmentGroup(int slot, String language, String timeZone) { this.slot=slot; this.language=language; this.timeZone=timeZone; }
        JSONObject json() throws JSONException { return new JSONObject().put("slot",slot).put("language",language).put("timeZone",timeZone); }
    }
    public static final class EnvironmentDelete {
        public final String storageId;
        public final int environmentSlot;
        public EnvironmentDelete(String storageId,int environmentSlot) { this.storageId=storageId;this.environmentSlot=environmentSlot; }
        JSONObject json() throws JSONException { return new JSONObject().put("storageId",storageId).put("environmentSlot",environmentSlot); }
    }
    public String activeAccount;
    public static String id() { return UUID.randomUUID().toString(); }
    public static String profile(String id) { if (!validId(id)) throw new IllegalArgumentException("Invalid profile ID"); return "profile-" + id; }
    private static boolean validId(String s) { return s != null && s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    public static final class Tab {
        public String id = WorkspaceState.id(), url = NavigationPolicy.HOME, title = "ChatGPT";
        public boolean pinned,customTitle;
        private String longPageTitle = "";

        String displayTitle() {
            String label = !customTitle && !longPageTitle.isEmpty() ? longPageTitle : title;
            return label.trim().isEmpty() ? "新标签页" : label;
        }

        boolean hasNewPageTitle(String value) {
            String page = boundedPageTitle(value);
            return !customTitle && !page.isEmpty() && !page.equals(displayTitle());
        }

        void rememberPageTitle(String value) {
            String page = boundedPageTitle(value);
            if (customTitle || page.isEmpty()) return;
            title = prefix(page, 120);
            longPageTitle = page.equals(title) ? "" : page;
        }

        private static String boundedPageTitle(String value) {
            return value == null || value.trim().isEmpty() ? "" : prefix(value, 4096);
        }

        private static String prefix(String value, int max) {
            if (value.length() <= max) return value;
            return value.substring(0, Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max);
        }

        JSONObject json() throws JSONException {
            JSONObject value = new JSONObject().put("id", id).put("url", url).put("title", title).put("pinned", pinned);
            if (customTitle) value.put("customTitle", true);
            if (!customTitle && !longPageTitle.isEmpty()) value.put("pageTitle", longPageTitle);
            return value;
        }
    }
    public static final class Account {
        public String id = WorkspaceState.id(), storageId = WorkspaceState.id(), name = "个人账号", email = "", color = COLORS[0], activeTab;
        public String engine = "webview", language, timeZone;
        public int environmentSlot = -1;
        private boolean explicitEngine;
        public boolean isEnvironment() { return "gecko".equals(engine); }
        public final List<Tab> tabs = new ArrayList<>();
        public final List<Tab> closedTabs = new ArrayList<>();
        public Account() { Tab t = new Tab(); tabs.add(t); activeTab = t.id; }
        public Tab current() { return tabs.stream().filter(t -> t.id.equals(activeTab)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")); }
        JSONObject json() throws JSONException {
            JSONArray ts = new JSONArray(), cs = new JSONArray();
            for (Tab t : tabs) ts.put(t.json()); for (Tab t : closedTabs) cs.put(t.json());
            JSONObject value = new JSONObject().put("id", id).put("storageId", storageId).put("name", name).put("email", email)
                    .put("color", color).put("activeTab", activeTab).put("tabs", ts).put("closedTabs", cs);
            if (explicitEngine || isEnvironment()) value.put("engine",engine);
            if (isEnvironment()) value.put("language",language).put("timeZone",timeZone).put("environmentSlot",environmentSlot);
            return value;
        }
    }
    public static WorkspaceState fresh() {
        WorkspaceState s = new WorkspaceState(); Account a = new Account(); s.accounts.add(a); s.activeAccount = a.id; return s;
    }
    public Account current() { return accounts.stream().filter(a -> a.id.equals(activeAccount)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")); }
    public Account find(String accountId) { return accounts.stream().filter(a -> a.id.equals(accountId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")); }
    public Account addAccount(String name, String email, String color) {
        if (accounts.size() >= MAX_ACCOUNTS) throw new IllegalStateException("最多 30 个账号空间");
        Account a = new Account(); a.name = label(name, 40); a.email = label(email, 120); a.color = color; accounts.add(a); activeAccount = a.id; return a;
    }
    public Account addEnvironmentAccount(String name,String email,String color,String language,String timeZone) {
        if(accounts.size()>=MAX_ACCOUNTS)throw new IllegalStateException("最多 30 个账号空间");
        String locale=EnvironmentConfig.canonicalLanguage(language),zone=EnvironmentConfig.canonicalTimeZone(timeZone);
        EnvironmentGroup group=null;
        for(EnvironmentGroup existing:environmentGroups)if(existing.language.equals(locale)&&existing.timeZone.equals(zone)){group=existing;break;}
        if(group==null){
            if(environmentGroups.size()>=EnvironmentConfig.MAX_SLOTS)throw new IllegalStateException("已使用 30 种环境，请选择已有语言和时区组合");
            int slot=0;Set<Integer> used=new HashSet<>();for(EnvironmentGroup existing:environmentGroups)used.add(existing.slot);while(used.contains(slot))slot++;
            group=new EnvironmentGroup(slot,locale,zone);environmentGroups.add(group);
        }
        Account a=addAccount(name,email,color);a.engine="gecko";a.language=locale;a.timeZone=zone;a.environmentSlot=group.slot;return a;
    }
    private void queueDelete(Account a) {
        if(a.isEnvironment())pendingEnvironmentDeletes.add(new EnvironmentDelete(a.storageId,a.environmentSlot));
        else pendingDeletes.add(a.storageId);
    }
    public Tab addTab(Account a) {
        if (a.tabs.size() >= MAX_TABS) throw new IllegalStateException("每个账号最多 30 个标签页");
        Tab t = new Tab(); a.tabs.add(t); a.activeTab = t.id; return t;
    }
    public void closeTab(Account a, String tabId) {
        Tab t = a.tabs.stream().filter(x -> x.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab"));
        a.closedTabs.add(t); if (a.closedTabs.size() > 10) a.closedTabs.remove(0);
        int index = a.tabs.indexOf(t); a.tabs.remove(t);
        if (a.tabs.isEmpty()) { Tab fresh = new Tab(); a.tabs.add(fresh); }
        if (a.activeTab.equals(tabId)) a.activeTab = a.tabs.get(Math.min(index, a.tabs.size() - 1)).id;
    }
    public void clearAccount(Account a) {
        queueDelete(a); a.storageId = id(); a.tabs.clear(); a.closedTabs.clear();
        Tab t = new Tab(); a.tabs.add(t); a.activeTab = t.id;
    }
    public void removeAccount(Account a) {
        if (accounts.size() == 1) throw new IllegalStateException("请至少保留一个账号空间");
        queueDelete(a); accounts.remove(a); if (activeAccount.equals(a.id)) activeAccount = accounts.get(0).id;
    }
    public WorkspaceState copy() { try { return parse(json().toString()); } catch (JSONException e) { throw new IllegalStateException(e); } }
    public JSONObject json() throws JSONException {
        validate(); JSONArray aa = new JSONArray(), dd = new JSONArray();
        for (Account a : accounts) aa.put(a.json()); for (String d : pendingDeletes) dd.put(d);
        JSONObject value=new JSONObject().put("schema", 1).put("activeAccount", activeAccount).put("accounts", aa).put("pendingDeletes", dd);
        if(!environmentGroups.isEmpty()){JSONArray groups=new JSONArray();for(EnvironmentGroup group:environmentGroups)groups.put(group.json());value.put("environmentGroups",groups);}
        if(!pendingEnvironmentDeletes.isEmpty()){JSONArray deletes=new JSONArray();for(EnvironmentDelete item:pendingEnvironmentDeletes)deletes.put(item.json());value.put("pendingEnvironmentDeletes",deletes);}
        return value;
    }
    public static WorkspaceState parse(String raw) throws JSONException {
        JSONObject j = new JSONObject(raw); if (j.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported state version");
        WorkspaceState s = new WorkspaceState(); s.activeAccount = j.getString("activeAccount");
        JSONArray aa = j.getJSONArray("accounts");
        if (aa.length() > MAX_ACCOUNTS) throw new IllegalArgumentException("Too many accounts");
        for (int i = 0; i < aa.length(); i++) {
            JSONObject v = aa.getJSONObject(i); Account a = new Account(); a.tabs.clear();
            a.id = v.getString("id"); a.storageId = v.getString("storageId"); a.name = v.getString("name");
            a.explicitEngine=v.has("engine");a.engine=v.has("engine")?v.getString("engine"):"webview";
            if(a.isEnvironment()){a.language=v.getString("language");a.timeZone=v.getString("timeZone");a.environmentSlot=integer(v,"environmentSlot");}
            else if(v.has("language")||v.has("timeZone")||v.has("environmentSlot"))throw new IllegalArgumentException("Unexpected WebView environment");
            a.email = v.getString("email"); a.color = v.getString("color"); a.activeTab = v.getString("activeTab");
            readTabs(v.getJSONArray("tabs"), a.tabs, MAX_TABS); readTabs(v.getJSONArray("closedTabs"), a.closedTabs, 10); s.accounts.add(a);
        }
        JSONArray dd = j.getJSONArray("pendingDeletes"); if (dd.length() > 1000) throw new IllegalArgumentException("Too many pending profiles");
        for (int i = 0; i < dd.length(); i++) s.pendingDeletes.add(dd.getString(i));
        JSONArray groups=j.optJSONArray("environmentGroups"),deletes=j.optJSONArray("pendingEnvironmentDeletes");
        if(j.has("environmentGroups")&&groups==null||j.has("pendingEnvironmentDeletes")&&deletes==null)throw new IllegalArgumentException("Invalid environment records");
        if(groups!=null){if(groups.length()>EnvironmentConfig.MAX_SLOTS)throw new IllegalArgumentException("Too many environments");for(int i=0;i<groups.length();i++){JSONObject g=groups.getJSONObject(i);s.environmentGroups.add(new EnvironmentGroup(integer(g,"slot"),g.getString("language"),g.getString("timeZone")));}}
        if(deletes!=null){if(deletes.length()>1000)throw new IllegalArgumentException("Too many pending environments");for(int i=0;i<deletes.length();i++){JSONObject d=deletes.getJSONObject(i);s.pendingEnvironmentDeletes.add(new EnvironmentDelete(d.getString("storageId"),integer(d,"environmentSlot")));}}
        s.validate(); return s;
    }
    private static int integer(JSONObject value,String key)throws JSONException {Object raw=value.get(key);if(!(raw instanceof Number)||((Number)raw).doubleValue()!=((Number)raw).intValue())throw new IllegalArgumentException("Invalid integer");return ((Number)raw).intValue();}
    private static void readTabs(JSONArray list, List<Tab> into, int max) throws JSONException {
        if (list.length() > max) throw new IllegalArgumentException("Too many tabs");
        for (int i = 0; i < list.length(); i++) {
            JSONObject j = list.getJSONObject(i); Tab t = new Tab(); t.id = j.getString("id"); t.url = j.getString("url");
            t.title = j.getString("title"); t.pinned = j.getBoolean("pinned");if(j.has("customTitle")&&!(j.get("customTitle") instanceof Boolean))throw new IllegalArgumentException("Invalid custom title flag");t.customTitle=j.optBoolean("customTitle",false);
            Object pageTitle = j.opt("pageTitle");
            if (pageTitle instanceof String && !t.customTitle) t.longPageTitle = Tab.boundedPageTitle((String) pageTitle);
            into.add(t);
        }
    }
    public void validate() {
        if (accounts.isEmpty() || accounts.size() > MAX_ACCOUNTS) throw new IllegalArgumentException("Invalid account count");
        Set<Integer> slots=new HashSet<>();Set<String> combinations=new HashSet<>();
        if(environmentGroups.size()>EnvironmentConfig.MAX_SLOTS)throw new IllegalArgumentException("Too many environments");
        for(EnvironmentGroup group:environmentGroups){EnvironmentConfig.validateSlot(group.slot);validateEnvironment(group.language,group.timeZone);if(!slots.add(group.slot)||!combinations.add(group.language+"/"+group.timeZone))throw new IllegalArgumentException("Duplicate environment");}
        Set<String> ids = new HashSet<>(), stores = new HashSet<>();
        for (Account a : accounts) {
            if (!validId(a.id) || !ids.add(a.id) || !validId(a.storageId) || !stores.add(a.storageId)
                    || a.name.trim().isEmpty() || a.name.length() > 40 || a.email.length() > 120 || !java.util.Arrays.asList(COLORS).contains(a.color)
                    || a.tabs.isEmpty() || a.tabs.size() > MAX_TABS || a.closedTabs.size() > 10) throw new IllegalArgumentException("Invalid account");
            if(a.isEnvironment()){
                validateEnvironment(a.language,a.timeZone);EnvironmentConfig.validateSlot(a.environmentSlot);
                boolean matched=false;for(EnvironmentGroup group:environmentGroups)if(group.slot==a.environmentSlot&&group.language.equals(a.language)&&group.timeZone.equals(a.timeZone))matched=true;
                if(!matched)throw new IllegalArgumentException("Missing account environment");
            }else if(!"webview".equals(a.engine)||a.language!=null||a.timeZone!=null||a.environmentSlot!=-1)throw new IllegalArgumentException("Invalid engine");
            Set<String> ts = new HashSet<>();
            for (Tab t : a.tabs) validateTab(t, ts, a.isEnvironment());
            if (!ts.contains(a.activeTab)) throw new IllegalArgumentException("Missing active tab");
            for (Tab t : a.closedTabs) validateTab(t, ts, a.isEnvironment());
        }
        if (!ids.contains(activeAccount)) throw new IllegalArgumentException("Missing active account");
        Set<String> deleted = new HashSet<>();
        if(pendingDeletes.size()>1000||pendingEnvironmentDeletes.size()>1000)throw new IllegalArgumentException("Too many pending profiles");
        for (String d : pendingDeletes) if (!validId(d) || stores.contains(d) || !deleted.add(d)) throw new IllegalArgumentException("Invalid deletion queue");
        for(EnvironmentDelete d:pendingEnvironmentDeletes){EnvironmentConfig.validateSlot(d.environmentSlot);if(!validId(d.storageId)||stores.contains(d.storageId)||!deleted.add(d.storageId)||!slots.contains(d.environmentSlot))throw new IllegalArgumentException("Invalid environment deletion queue");}
    }
    private static void validateEnvironment(String language,String timeZone){if(!EnvironmentConfig.canonicalLanguage(language).equals(language)||!EnvironmentConfig.canonicalTimeZone(timeZone).equals(timeZone))throw new IllegalArgumentException("Noncanonical environment");}
    private static void validateTab(Tab t, Set<String> ids, boolean environment) {
        if (!validId(t.id) || !ids.add(t.id) || !(NavigationPolicy.chat(t.url) || environment && EnvironmentPolicy.testUrl(t.url)) || t.title.length() > 120) throw new IllegalArgumentException("Invalid tab");
    }
    private static String label(String text, int max) { String s = text == null ? "" : text.trim(); if (s.length() > max) throw new IllegalArgumentException("备注过长"); return s; }
}
