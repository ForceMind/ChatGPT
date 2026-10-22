package com.forcemind.chatgpt.profiles;

import java.util.Locale;
import org.json.JSONObject;

/** Known filename extensions take precedence over unreliable provider MIME. */
final class ConversionType {
    static String of(JSONObject source){
        String name=source.optString("name").toLowerCase(Locale.ROOT),mime=source.optString("mime");
        int dot=name.lastIndexOf('.');String ext=dot<0?"":name.substring(dot);
        switch(ext){
            case ".svg":return "svg";case ".html":case ".htm":return "html";
            case ".md":case ".markdown":return "markdown";case ".pdf":return "pdf";
            case ".csv":return "csv";case ".json":return "json";
            case ".png":case ".jpg":case ".jpeg":case ".webp":case ".gif":case ".avif":case ".bmp":case ".heic":case ".heif":return "image";
            default:break;
        }
        switch(mime){case "image/svg+xml":return "svg";case "text/html":return "html";case "text/markdown":return "markdown";case "application/pdf":return "pdf";case "text/csv":return "csv";case "application/json":return "json";default:return mime.startsWith("image/")?"image":"";}
    }
}
