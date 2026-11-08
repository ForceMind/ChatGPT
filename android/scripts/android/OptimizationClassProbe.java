package com.forcemind.chatgpt.pocinput;
import dalvik.system.DexClassLoader;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;
/** Read metadata only: no constructors, app entry points or webpages are invoked. */
public final class OptimizationClassProbe {
 public static void main(String[] args)throws Exception {
  if(args.length!=1)throw new SecurityException("Owned emulator required");
  File apk=new File(args[0]).getCanonicalFile();if(!apk.isFile()||!apk.getPath().startsWith("/data/app/"))throw new SecurityException("Installed APK required");
  File cache=new File("/data/local/tmp/studio-class-probe-cache");if(!cache.isDirectory()&&!cache.mkdirs())throw new IllegalStateException("Probe cache unavailable");
  ClassLoader loader=new DexClassLoader(apk.getPath(),cache.getPath(),null,ClassLoader.getSystemClassLoader());
  JSONArray rows=new JSONArray();
  for(String name:new String[]{"com.forcemind.chatgpt.profiles.EnvironmentBrowserActivity$Slot0","com.forcemind.chatgpt.profiles.EnvironmentBrowserActivity$Slot1","androidx.tracing.Trace"}){
   JSONObject row=new JSONObject().put("requested",name);
   try{Class<?> type=Class.forName(name,false,loader);Class<?> enclosing=type.getEnclosingClass();row.put("present",true).put("name",type.getName()).put("simpleName",type.getSimpleName()).put("enclosing",enclosing==null?JSONObject.NULL:enclosing.getName());}
   catch(ClassNotFoundException|LinkageError error){row.put("present",false).put("errorClass",error.getClass().getName());}
   rows.put(row);
  }
  JSONObject abi=new JSONObject();
  try{Class<?> state=Class.forName("com.forcemind.chatgpt.profiles.WorkspaceState",false,loader);abi.put("present",true).put("accountsType",state.getDeclaredField("accounts").getType().getName()).put("freshReturnType",state.getDeclaredMethod("fresh").getReturnType().getName());}
  catch(ReflectiveOperationException|LinkageError error){abi.put("present",false).put("errorClass",error.getClass().getName());}
  System.out.println(new JSONObject().put("schema",1).put("complete",true).put("readOnly",true).put("applicationConstructorsInvoked",false).put("webpagesOpened",0).put("rows",rows).put("workspaceApi",abi).toString());
 }
}
