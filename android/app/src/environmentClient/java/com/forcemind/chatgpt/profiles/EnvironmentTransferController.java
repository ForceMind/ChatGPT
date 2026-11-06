package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.*;
import java.util.*;
import org.json.JSONObject;
import org.mozilla.geckoview.*;

/** Per-request capabilities. No request bodies or selected content URIs cross the route port. */
final class EnvironmentTransferController {
    static final String HEADER="x-studio-environment-ticket",BIND="about:blank#studio-network-bind-";
    interface LeaseCallback{void complete(Lease lease);}
    interface CheckCallback{void complete(boolean valid);}
    static final class Lease {
        final String id,instance,purpose;final long generation;final EnvironmentBrowserActivity.Entry owner;String uri,call;volatile boolean closed;boolean claimed,selected,downloadCandidate,completed;Runnable onRevoke;
        Lease(String id,String purpose,EnvironmentBrowserActivity.Entry owner,String uri,JSONObject route)throws Exception{this.id=id;this.purpose=purpose;this.owner=owner;this.uri=uri;instance=route.getString("instance");generation=route.getLong("generation");}
    }
    private static final class Binding {
        final String nonce=UUID.randomUUID().toString(),call,uri,method;final JSONObject route;final WebExtension.Port port;boolean cancelled;
        Binding(JSONObject value,WebExtension.Port port)throws Exception{call=value.getString("call");UUID.fromString(call);uri=value.getString("uri");method=value.getString("method");route=new JSONObject(value.toString());this.port=port;}
    }
    private final Context context;private final EnvironmentRouteController routes;private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,Binding> bindings=new HashMap<>();private final Map<String,Binding> bindingCalls=new HashMap<>();private final Map<String,Lease> leases=new HashMap<>();private final Map<EnvironmentBrowserActivity.Entry,Lease> pickers=new HashMap<>();
    EnvironmentTransferController(Context context,EnvironmentRouteController routes){this.context=context.getApplicationContext();this.routes=routes;}
    private boolean owns(EnvironmentBrowserActivity.Entry e){
        if(e==null||e.closed||EnvironmentBrowserActivity.SESSIONS.get(e.tabId)!=e)return false;
        try{WorkspaceState.Account a=new StateStore(context.getFilesDir()).load().find(e.accountId);return a.isEnvironment()&&a.environmentSlot==routes.slot&&a.storageId.equals(e.storageId)&&a.tabs.stream().anyMatch(t->t.id.equals(e.tabId));}catch(Exception unavailable){return false;}
    }
    private JSONObject owner(EnvironmentBrowserActivity.Entry e,String purpose,String uri,JSONObject route)throws Exception{
        return new JSONObject().put("operation",UUID.randomUUID().toString()).put("purpose",purpose).put("accountId",e.accountId).put("storageId",e.storageId).put("tabId",e.tabId).put("url",e.url).put("uri",uri).put("instance",route.getString("instance")).put("generation",route.getLong("generation"));
    }
    void attach(EnvironmentBrowserActivity.Entry e,WebExtension extension){
        e.session.getWebExtensionController().setTabDelegate(extension,new WebExtension.SessionTabDelegate(){
            @Override public GeckoResult<AllowOrDeny> onUpdateTab(WebExtension source,GeckoSession session,WebExtension.UpdateTabDetails details){
                String value=details.url;
                if(BuildConfig.ENVIRONMENT_TEST&&value!=null&&value.startsWith(BIND))android.util.Log.i("StudioEnvironmentRoute","native binding callback slot="+routes.slot+" ownerValid="+owns(e));
                if(value!=null&&value.startsWith(BIND)&&source!=null&&EnvironmentRouteController.EXTENSION.equals(source.id)&&session==e.session&&details.active==null&&details.muted==null&&details.pinned==null&&details.highlighted==null&&details.autoDiscardable==null){
                    Binding binding=bindings.remove(value.substring(BIND.length()));
                    if(binding!=null){if(value.equals(BIND+binding.nonce)&&owns(e)&&routes.matches(binding.route))acquireRequest(binding,e);else reply(binding,null);}
                }
                // Gecko 158 loads the supplied URL only after ALLOW. This proof never navigates.
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
        });
    }
    private void reply(Binding binding,Lease lease){
        bindingCalls.remove(binding.call);if(BuildConfig.ENVIRONMENT_TEST)android.util.Log.i("StudioEnvironmentRoute","request capability slot="+routes.slot+" ok="+(lease!=null)+" ownerBound="+(lease!=null&&lease.owner!=null));if(binding.cancelled){if(lease!=null)release(lease,"cancel");return;}
        try{JSONObject value=new JSONObject().put("type","request-approved").put("call",binding.call).put("ok",lease!=null);if(lease!=null)value.put("ticket",lease.id).put("ownerBound",lease.owner!=null);routes.post(binding.port,value);}catch(Exception invalid){if(lease!=null)release(lease,"cancel");}
    }
    private void acquireRequest(Binding binding,EnvironmentBrowserActivity.Entry e){
        try{JSONObject data=e!=null?owner(e,"request",binding.uri,binding.route):new JSONObject().put("operation",UUID.randomUUID().toString()).put("purpose","runtime").put("url",binding.uri).put("uri",binding.uri).put("instance",binding.route.getString("instance")).put("generation",binding.route.getLong("generation"));routes.broker(6,data,result->{
            Lease lease=null;try{if(result.optBoolean("ok")&&(e==null||owns(e))&&routes.matches(binding.route)){lease=new Lease(result.getString("lease"),e==null?"runtime":"request",e,binding.uri,binding.route);lease.call=binding.call;leases.put(lease.id,lease);}else if(result.optBoolean("ok"))releaseRemote(result.optString("lease"),"cancel");}catch(Exception invalid){if(result.optBoolean("ok"))releaseRemote(result.optString("lease"),"cancel");}
            reply(binding,lease);
        });}catch(Exception invalid){reply(binding,null);}
    }
    boolean message(JSONObject value,WebExtension.Port source){
        String type=value.optString("type");
        if("request-start".equals(type)){
            try{
                Binding binding=new Binding(value,source);
                if(!routes.matches(value)||bindingCalls.size()>=256){reply(binding,null);return true;}
                if(value.getLong("tab")<0){bindingCalls.put(binding.call,binding);acquireRequest(binding,null);return true;}
                bindings.put(binding.nonce,binding);bindingCalls.put(binding.call,binding);routes.post(source,new JSONObject().put("type","bind-required").put("call",binding.call).put("nonce",binding.nonce).put("url",BIND+binding.nonce));
                ui.postDelayed(()->{if(bindings.remove(binding.nonce)==binding)reply(binding,null);},5000);
            }catch(Exception invalid){/* An invalid capability request is never permitted. */}
            return true;
        }
        if("request-cancel".equals(type)){
            String call=value.optString("call");Binding binding=bindingCalls.remove(call);if(binding!=null){binding.cancelled=true;bindings.remove(binding.nonce);}for(Lease lease:new ArrayList<>(leases.values()))if(call.equals(lease.call))release(lease,"cancel");return true;
        }
        if(!java.util.Arrays.asList("request-response","request-redirect","request-finished").contains(type))return false;
        Lease lease=leases.get(value.optString("ticket"));String call=value.optString("call");
        if(lease==null||lease.closed||(lease.owner!=null&&!owns(lease.owner))||!routes.matches(lease.instance,lease.generation)){
            if(lease!=null)release(lease,"cancel");respond(source,call,false);return true;
        }
        if("request-finished".equals(type)){release(lease,value.optBoolean("success")?"completed":"error");return true;}
        try{
            String uri=value.getString("uri");if("request-response".equals(type)&&!lease.uri.equals(uri)){respond(source,call,false);return true;}
            JSONObject data=new JSONObject().put("lease",lease.id).put("instance",lease.instance).put("generation",lease.generation).put("action","request-response".equals(type)?"response":"redirect").put("uri",uri).put("download",value.optBoolean("download"));
            routes.broker(8,data,result->{boolean ok=result.optBoolean("ok")&&(lease.owner==null||owns(lease.owner))&&routes.matches(lease.instance,lease.generation)&&!lease.closed;if(ok&&"request-redirect".equals(type))lease.uri=uri;if(ok&&"request-response".equals(type))lease.downloadCandidate=lease.owner!=null&&value.optBoolean("download");respond(source,call,ok);});
        }catch(Exception invalid){respond(source,call,false);}
        return true;
    }
    private void respond(WebExtension.Port source,String call,boolean ok){try{routes.post(source,new JSONObject().put("type","request-result").put("call",call).put("ok",ok));}catch(Exception invalid){/* Caller remains blocked until its bounded request timeout. */}}
    void acquirePicker(EnvironmentBrowserActivity.Entry e,LeaseCallback callback){
        if(!owns(e)||!routes.ready()){callback.complete(null);return;}
        try{JSONObject tuple=routes.tuple();routes.broker(6,owner(e,"picker",e.url,tuple),result->{
            Lease lease=null;try{if(result.optBoolean("ok")&&owns(e)&&routes.matches(tuple)){lease=new Lease(result.getString("lease"),"picker",e,e.url,tuple);leases.put(lease.id,lease);Lease previous=pickers.put(e,lease);if(previous!=null)release(previous,"cancel");}else if(result.optBoolean("ok"))releaseRemote(result.optString("lease"),"cancel");}catch(Exception invalid){if(result.optBoolean("ok"))releaseRemote(result.optString("lease"),"cancel");}callback.complete(lease);
        });}catch(Exception invalid){callback.complete(null);}
    }
    void validatePicker(Lease lease,CheckCallback callback){
        if(lease==null||!"picker".equals(lease.purpose)||lease.closed||!owns(lease.owner)||!routes.matches(lease.instance,lease.generation)){callback.complete(false);return;}
        try{routes.broker(8,new JSONObject().put("lease",lease.id).put("instance",lease.instance).put("generation",lease.generation).put("action","validate"),result->callback.complete(result.optBoolean("ok")&&!lease.closed&&owns(lease.owner)&&routes.matches(lease.instance,lease.generation)));}catch(Exception invalid){callback.complete(false);}
    }
    void selected(Lease lease){if(lease!=null){lease.selected=true;pickers.remove(lease.owner);release(lease,"cancel");}}
    Lease claim(EnvironmentBrowserActivity.Entry e,String ticket,String uri){Lease lease=leases.get(ticket);if(lease==null||lease.closed||lease.claimed||!"request".equals(lease.purpose)||lease.owner!=e||!lease.uri.equals(uri)||!owns(e)||!routes.matches(lease.instance,lease.generation))return null;lease.claimed=true;return lease;}
    void release(Lease lease,String status){
        if(lease==null)return;if("completed".equals(status)&&"request".equals(lease.purpose)&&(lease.downloadCandidate||lease.claimed)){lease.completed=true;if(!lease.claimed)ui.postDelayed(()->{if(!lease.claimed&&lease.completed)release(lease,"cancel");},30000);}else{revokeLease(lease);leases.remove(lease.id);if(pickers.get(lease.owner)==lease)pickers.remove(lease.owner);}releaseRemote(lease.id,status);
    }
    private void releaseRemote(String id,String status){try{routes.sendPayload(7,new JSONObject().put("lease",id).put("status",status));}catch(Exception ignored){/* Binder loss is handled by broker death and route revocation. */}}
    private void revokeLease(Lease lease){lease.closed=true;Runnable callback=lease.onRevoke;lease.onRevoke=null;if(callback!=null)callback.run();}
    void imported(Lease lease){if(lease!=null){revokeLease(lease);leases.remove(lease.id);releaseRemote(lease.id,"cancel");}}
    void expired(String id){Lease lease=leases.remove(id);if(lease!=null){revokeLease(lease);if(pickers.get(lease.owner)==lease)pickers.remove(lease.owner);}}
    void closeEntry(EnvironmentBrowserActivity.Entry e){for(Lease lease:new ArrayList<>(leases.values()))if(lease.owner==e)release(lease,"cancel");pickers.remove(e);}
    void revoke(){for(Binding binding:new ArrayList<>(bindings.values()))reply(binding,null);bindings.clear();bindingCalls.clear();for(Lease lease:leases.values())revokeLease(lease);leases.clear();pickers.clear();try{routes.sendPayload(9,new JSONObject());}catch(Exception ignored){/* Dead broker revokes its leases independently. */}}
}
