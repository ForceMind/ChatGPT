package com.forcemind.chatgpt.profiles;

/** Production policy remains the existing ChatGPT policy; fixture access is flavor-owned. */
final class EnvironmentUrls {
    static boolean chat(String url){return NavigationPolicy.chat(url)||CIBuild.environmentUrl(url);}
    static String recoverable(String url){String safe=PageRecoveryRecord.url(url);return safe!=null?safe:(CIBuild.environmentUrl(url)?url:null);}
}
