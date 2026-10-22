package com.forcemind.chatgpt.profiles;

import java.io.IOException;
import java.util.*;

final class PageSelection {
    static List<Integer> parse(String value)throws IOException{
        String input=value==null?"1":value.trim().replace('，',',');
        if(input.isEmpty()||input.length()>256)throw new IOException("请填写页码，例如 1,3-5，一次最多 20 页");
        LinkedHashSet<Integer> pages=new LinkedHashSet<>();
        try{
            for(String part:input.split(",",-1)){
                if(!part.trim().matches("[1-9][0-9]*(?:\\s*-\\s*[1-9][0-9]*)?"))throw new IOException("页码无效，请使用 1,3-5 这样的格式");
                String[] range=part.trim().split("\\s*-\\s*");int first=Integer.parseInt(range[0]),last=range.length==1?first:Integer.parseInt(range[1]);
                if(last<first||(long)last-first+1>20)throw new IOException("页码范围无效，一次最多 20 页");
                for(long page=first;page<=last;page++)if(!pages.add((int)page)||pages.size()>20)throw new IOException("页码不能重复，一次最多 20 页");
            }
        }catch(NumberFormatException e){throw new IOException("页码超出范围",e);}
        return new ArrayList<>(pages);
    }
}
