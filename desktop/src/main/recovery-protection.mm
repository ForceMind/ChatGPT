#import <Foundation/Foundation.h>
#import <LocalAuthentication/LocalAuthentication.h>
#import <Security/Security.h>
#include <node_api.h>
#include <string>
static void erase(void *buffer,size_t size){volatile unsigned char *p=(volatile unsigned char *)buffer;while(size--)*p++=0;}

// Runs inside the signed main process: the system access control protects the
// actual encryption key, rather than trusting a separate biometric prompt.
static bool entitled() {
  SecCodeRef code=nullptr;CFDictionaryRef information=nullptr;
  if(SecCodeCopySelf(kSecCSDefaultFlags,&code)!=errSecSuccess)return false;
  bool result=false;
  if(SecCodeCopySigningInformation(code,kSecCSSigningInformation,&information)==errSecSuccess){
    NSDictionary *info=(__bridge NSDictionary *)information;
    NSString *team=info[(__bridge id)kSecCodeInfoTeamIdentifier];
    NSDictionary *entitlements=info[(__bridge id)kSecCodeInfoEntitlementsDict];
    NSString *identifier=entitlements[@"com.apple.application-identifier"];
    result=team.length>0 && [info[(__bridge id)kSecCodeInfoIdentifier] isEqual:@"com.forcemind.chatgpt.profiles"] && [entitlements[@"com.apple.developer.team-identifier"] isEqual:team] && [identifier isEqual:[team stringByAppendingString:@".com.forcemind.chatgpt.profiles"]];
  }
  if(information)CFRelease(information);CFRelease(code);return result;
}
static napi_value capabilities(napi_env env, napi_callback_info) {
  @autoreleasepool {
    LAContext *context = [[LAContext alloc] init]; NSError *error = nil;
    bool biometric = [context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&error];
    napi_value object, value; napi_create_object(env,&object);
    napi_get_boolean(env,biometric,&value); napi_set_named_property(env,object,"touchID",value);
    napi_get_boolean(env,entitled(),&value); napi_set_named_property(env,object,"signed",value);
    return object;
  }
}
struct Request { napi_async_work work; napi_deferred deferred; std::string slot; bool create; unsigned char key[32] = {}; OSStatus status = errSecSuccess; };
static NSMutableDictionary *query(NSString *slot) {
  return [@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService:@"com.forcemind.chatgpt.profiles.recovery-presence.v1",
    (__bridge id)kSecAttrAccount:slot, (__bridge id)kSecUseDataProtectionKeychain:@YES} mutableCopy];
}
static void execute(napi_env, void *data) {
  Request *request = static_cast<Request *>(data);
  @autoreleasepool {
    if(!entitled()){request->status=errSecMissingEntitlement;return;}
    NSString *slot = [NSString stringWithUTF8String:request->slot.c_str()];
    LAContext *context = [[LAContext alloc] init];
    context.localizedReason = @"解锁 ChatGPT Profiles 本机保存的草稿和离线页面";
    NSMutableDictionary *lookup = query(slot);
    lookup[(__bridge id)kSecReturnData]=@YES;
    lookup[(__bridge id)kSecUseAuthenticationContext]=context;
    CFTypeRef result = nullptr;
    request->status = SecItemCopyMatching((__bridge CFDictionaryRef)lookup,&result);
    if(request->status==errSecItemNotFound && request->create){
      unsigned char generated[32]; request->status=SecRandomCopyBytes(kSecRandomDefault,sizeof(generated),generated);
      if(request->status!=errSecSuccess)return;
      CFErrorRef error=nullptr;
      SecAccessControlRef control=SecAccessControlCreateWithFlags(kCFAllocatorDefault,kSecAttrAccessibleWhenUnlockedThisDeviceOnly,kSecAccessControlUserPresence,&error);
      if(!control){erase(generated,sizeof(generated));if(error)CFRelease(error);request->status=errSecParam;return;}
      NSMutableDictionary *attributes=query(slot);
      attributes[(__bridge id)kSecAttrAccessControl]=(__bridge id)control;
      attributes[(__bridge id)kSecValueData]=[NSData dataWithBytes:generated length:sizeof(generated)];
      attributes[(__bridge id)kSecAttrLabel]=@"ChatGPT Profiles · 本地恢复（Touch ID）";
      request->status=SecItemAdd((__bridge CFDictionaryRef)attributes,nullptr);
      erase(generated,sizeof(generated)); CFRelease(control);
      if(request->status==errSecSuccess || request->status==errSecDuplicateItem)
        request->status=SecItemCopyMatching((__bridge CFDictionaryRef)lookup,&result);
    }
    if(request->status==errSecSuccess){
      if(!result || CFGetTypeID(result)!=CFDataGetTypeID() || CFDataGetLength((CFDataRef)result)!=32)request->status=errSecDecode;
      else memcpy(request->key,CFDataGetBytePtr((CFDataRef)result),32);
    }
    if(result)CFRelease(result);
    [context invalidate];
  }
}
static void complete(napi_env env,napi_status status,void *data) {
  Request *r=static_cast<Request *>(data); napi_value value;
  if(status==napi_ok && r->status==errSecSuccess){napi_create_buffer_copy(env,32,r->key,nullptr,&value);napi_resolve_deferred(env,r->deferred,value);}
  else {std::string text="本地恢复密钥未解锁（系统状态 "+std::to_string(r->status)+"）。原件保留，请重试。";napi_value message;napi_create_string_utf8(env,text.c_str(),NAPI_AUTO_LENGTH,&message);napi_create_error(env,nullptr,message,&value);napi_reject_deferred(env,r->deferred,value);}
  erase(r->key,sizeof(r->key));napi_delete_async_work(env,r->work);delete r;
}
static napi_value unlock(napi_env env,napi_callback_info info) {
  napi_value args[2];size_t count=2;napi_get_cb_info(env,info,&count,args,nullptr,nullptr);
  char slot[65]={};size_t length=0;bool create=false;
  if(count!=2 || napi_get_value_string_utf8(env,args[0],slot,sizeof(slot),&length)!=napi_ok || length!=64 || napi_get_value_bool(env,args[1],&create)!=napi_ok){napi_throw_type_error(env,nullptr,"Invalid recovery key request");return nullptr;}
  for(size_t i=0;i<length;i++)if(!isxdigit((unsigned char)slot[i])){napi_throw_type_error(env,nullptr,"Invalid recovery key slot");return nullptr;}
  Request *r=new Request();r->slot=slot;r->create=create;
  napi_value promise,name;napi_create_promise(env,&r->deferred,&promise);napi_create_string_utf8(env,"RecoveryKey",NAPI_AUTO_LENGTH,&name);
  napi_create_async_work(env,nullptr,name,execute,complete,r,&r->work);napi_queue_async_work(env,r->work);return promise;
}
static napi_value initialize(napi_env env,napi_value exports){
  napi_value value;napi_create_function(env,"capabilities",NAPI_AUTO_LENGTH,capabilities,nullptr,&value);napi_set_named_property(env,exports,"capabilities",value);
  napi_create_function(env,"unlock",NAPI_AUTO_LENGTH,unlock,nullptr,&value);napi_set_named_property(env,exports,"unlock",value);return exports;
}
NAPI_MODULE(NODE_GYP_MODULE_NAME, initialize)
