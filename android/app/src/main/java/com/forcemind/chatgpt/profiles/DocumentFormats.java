package com.forcemind.chatgpt.profiles;

import com.google.gson.Strictness;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Offline formats only. No type inference, network fetches, or native bridge. */
final class DocumentFormats {
    static final int MAX_COLUMNS=2048, MAX_ROWS=50000, MAX_CELLS=200000;
    private static void active()throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("转换已取消");
    }
    static char delimiter(String input)throws IOException{
        char selected=',';int best=1;
        for(char separator:new char[]{',','\t',';','|'}){
            active();
            try(CSVParser parser=csv(input,separator)){
                int count=0,columns=0;boolean consistent=true;
                for(CSVRecord row:parser){
                    if(blank(row))continue;
                    if(count++==0)columns=row.size();else if(row.size()!=columns){consistent=false;break;}
                    if(count==20)break;
                }
                if(consistent&&columns>best){selected=separator;best=columns;}
            }catch(UncheckedIOException|IllegalArgumentException|IOException ignored){/* another explicit delimiter may apply */}
        }
        return selected;
    }
    private static CSVParser csv(String input,char separator)throws IOException{
        if(separator!=','&&separator!='\t'&&separator!=';'&&separator!='|')throw new IOException("CSV 分隔符无效");
        String text=input.startsWith("\uFEFF")?input.substring(1):input;
        // Bound columns before the library allocates an entire CSVRecord. The
        // parser below still performs full quoting and syntax validation.
        boolean quoted=false,start=true;int columns=1;
        for(int i=0;i<text.length();i++){
            if((i&8191)==0)active();char c=text.charAt(i);
            if(quoted){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"')i++;else quoted=false;}continue;}
            if(c=='"'&&start){quoted=true;start=false;}
            else if(c==separator){if(++columns>MAX_COLUMNS)throw new IOException("CSV 列数超过 2048 列");start=true;}
            else if(c=='\r'||c=='\n'){columns=1;start=true;}
            else start=false;
        }
        return CSVFormat.RFC4180.builder().setDelimiter(separator).setIgnoreEmptyLines(true)
                .setLenientEof(false).setTrailingData(false).get().parse(new StringReader(text));
    }
    private static boolean blank(CSVRecord row){
        for(String cell:row)if(!cell.trim().isEmpty())return false;
        return true;
    }
    static void csvToJson(String input,char separator,Writer output)throws IOException{
        JsonWriter json=new JsonWriter(output);json.setIndent("  ");json.setStrictness(Strictness.STRICT);
        try(CSVParser parser=csv(input,separator)){
            List<String> fields=null;int rows=0,cells=0;json.beginArray();
            for(CSVRecord row:parser){
                active();if(blank(row))continue;
                if(row.size()>MAX_COLUMNS)throw new IOException("CSV 列数超过 2048 列");
                if(fields==null){
                    fields=new ArrayList<>();Set<String> names=new HashSet<>();
                    for(String field:row){if(field.trim().isEmpty()||!names.add(field))throw new IOException("CSV 表头不能为空或重复");fields.add(field);}
                    continue;
                }
                if(row.size()!=fields.size())throw new IOException("CSV 第 "+row.getRecordNumber()+" 行列数不一致");
                cells+=row.size();if(++rows>MAX_ROWS||cells>MAX_CELLS)throw new IOException("数据过多，请拆分后转换（最多 5 万行、20 万单元格）");
                json.beginObject();for(int i=0;i<fields.size();i++)json.name(fields.get(i)).value(row.get(i));json.endObject();
            }
            if(fields==null)throw new IOException("CSV 没有可转换的数据");
            json.endArray();json.flush();output.write('\n');
        }catch(UncheckedIOException e){throw new IOException("CSV 结构无效，请检查引号与分隔符",e);}
    }
    private static void csvCell(Writer output,String value,boolean protectFormula)throws IOException{
        if(protectFormula){
            int first=0;while(first<value.length()&&Character.isWhitespace(value.charAt(first)))first++;
            boolean control=!value.isEmpty()&&(value.charAt(0)=='\t'||value.charAt(0)=='\r'||value.charAt(0)=='\n');
            if(control||first<value.length()&&"=+-@".indexOf(value.charAt(first))>=0)value="'"+value;
        }
        output.write('"');output.write(value.replace("\"","\"\""));output.write('"');
    }
    static void jsonToCsv(String input,Writer output)throws IOException{
        try(JsonReader reader=new JsonReader(new StringReader(input.startsWith("\uFEFF")?input.substring(1):input))){
            reader.setStrictness(Strictness.STRICT);
            if(reader.peek()!=JsonToken.BEGIN_ARRAY)throw new IOException("JSON 转 CSV 需要非空的对象数组");
            reader.beginArray();List<String> fields=null;int rows=0,cells=0;output.write('\uFEFF');
            while(reader.hasNext()){
                active();if(reader.peek()!=JsonToken.BEGIN_OBJECT)throw new IOException("JSON 每一行必须是对象");
                reader.beginObject();LinkedHashMap<String,String> values=new LinkedHashMap<>();Set<String> strings=new HashSet<>();
                while(reader.hasNext()){
                    String name=reader.nextName();if(name.trim().isEmpty()||values.containsKey(name))throw new IOException("JSON 字段不能为空或重复");
                    if(values.size()>=MAX_COLUMNS)throw new IOException("JSON 字段超过 2048 列");
                    JsonToken token=reader.peek();String value;
                    switch(token){
                        case STRING:value=reader.nextString();strings.add(name);break;
                        case NUMBER:value=reader.nextString();try{if(!Double.isFinite(Double.parseDouble(value)))throw new NumberFormatException();}catch(NumberFormatException e){throw new IOException("非有限数值不能转换为 CSV",e);}break;
                        case BOOLEAN:value=String.valueOf(reader.nextBoolean());break;
                        case NULL:reader.nextNull();value="";break;
                        default:throw new IOException("嵌套对象或数组需要先清洗，不能直接转换为 CSV");
                    }
                    values.put(name,value);
                }
                reader.endObject();
                if(values.isEmpty())throw new IOException("JSON 对象没有可转换的字段");
                if(fields==null){fields=new ArrayList<>(values.keySet());for(int i=0;i<fields.size();i++){if(i>0)output.write(',');csvCell(output,fields.get(i),true);}output.write("\r\n");}
                else if(values.size()!=fields.size()||!values.keySet().containsAll(fields))throw new IOException("JSON 对象字段不一致，请先整理成相同结构");
                cells+=fields.size();if(++rows>MAX_ROWS||cells>MAX_CELLS)throw new IOException("数据过多，请拆分后转换（最多 5 万行、20 万单元格）");
                for(int i=0;i<fields.size();i++){if(i>0)output.write(',');String key=fields.get(i);csvCell(output,values.get(key),strings.contains(key));}output.write("\r\n");
            }
            reader.endArray();if(fields==null)throw new IOException("JSON 转 CSV 需要非空的对象数组");
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("JSON 后还有多余内容");
            output.flush();
        }catch(MalformedJsonException|IllegalStateException e){throw new IOException("JSON 语法无效，请检查原文件",e);}
    }
    static void markdownHtml(String input,Writer output)throws IOException{
        active();
        List<Extension> extensions=Arrays.asList(TablesExtension.create(),StrikethroughExtension.create(),TaskListItemsExtension.create(),AutolinkExtension.create());
        Parser parser=Parser.builder().extensions(extensions).build();
        HtmlRenderer renderer=HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build();
        output.write("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; base-uri 'none'; form-action 'none'\"><style>body{font-family:system-ui,sans-serif;max-width:800px;margin:32px auto;padding:0 20px;color:#171717;line-height:1.8;overflow-wrap:anywhere}pre{white-space:pre-wrap;background:#f5f5f5;padding:16px;border-radius:6px}code{font-family:monospace}table{border-collapse:collapse;display:block;overflow:auto}td,th{border:1px solid #ddd;padding:8px;text-align:left}img{max-width:100%;height:auto}blockquote{border-left:3px solid #ddd;margin-left:0;padding-left:16px;color:#666}@media print{body{margin:0;max-width:none}pre,blockquote{break-inside:avoid}}@page{size:A4;margin:14mm}</style></head><body>");
        renderer.render(parser.parse(input),output);active();output.write("</body></html>\n");output.flush();
    }
}
