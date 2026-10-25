package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Debug-only, token-gated loopback SSE. No files, credentials, real service or native bridge. */
final class LoopbackFixtureServer implements AutoCloseable {
    private final ServerSocket server;private final String token;private final Set<String> tabs;
    private final Set<String> active=ConcurrentHashMap.newKeySet();private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"fixture-http");t.setDaemon(true);return t;});
    LoopbackFixtureServer(String token,Set<String> tabs)throws IOException {
        this.token=token;this.tabs=new HashSet<>(tabs);server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        Thread acceptor=new Thread(()->{while(!server.isClosed())try{Socket client=server.accept();client.setSoTimeout(5000);client.setTcpNoDelay(true);clients.add(client);try{workers.execute(()->serve(client));}catch(RejectedExecutionException e){clients.remove(client);client.close();}}catch(IOException ignored){}},"fixture-http-accept");acceptor.setDaemon(true);acceptor.start();
    }
    String url(String tab){return "http://127.0.0.1:"+server.getLocalPort()+"/events?tab="+tab+"&token="+token;}
    boolean matches(String address,String tab){return tabs.contains(tab)&&url(tab).equals(address);}
    private static void write(OutputStream output,String text)throws IOException {output.write(text.getBytes(StandardCharsets.UTF_8));}
    private static void chunk(OutputStream output,String text)throws IOException {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);write(output,Integer.toHexString(bytes.length)+"\r\n");output.write(bytes);write(output,"\r\n");output.flush();}
    private void serve(Socket client){String current=null;
        try(Socket socket=client){
            BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();if(first==null||first.length()>1024)return;String[] request=first.split(" ");if(request.length!=3)return;
            String origin="",line;int size=first.length(),count=0;while((line=input.readLine())!=null&&!line.isEmpty()){size+=line.length();if(size>8192||++count>30)return;if(line.toLowerCase(Locale.ROOT).startsWith("origin:"))origin=line.substring(7).trim();}
            URI uri=new URI(request[1]);Map<String,String> query=new HashMap<>();for(String part:Objects.toString(uri.getRawQuery(),"").split("&")){int equals=part.indexOf('=');if(equals>0)query.put(part.substring(0,equals),part.substring(equals+1));}
            OutputStream output=socket.getOutputStream();String tab=query.get("tab");
            if(!"/events".equals(uri.getPath())||!token.equals(query.get("token"))||!tabs.contains(tab)||!origin.equals("https://chatgpt.com")||!(request[0].equals("GET")||request[0].equals("OPTIONS"))){write(output,"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");output.flush();return;}
            String cors="Access-Control-Allow-Origin: https://chatgpt.com\r\nAccess-Control-Allow-Private-Network: true\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nCache-Control: no-store\r\n";
            if(request[0].equals("OPTIONS")){write(output,"HTTP/1.1 204 No Content\r\n"+cors+"Content-Length: 0\r\nConnection: close\r\n\r\n");output.flush();return;}
            if(!active.add(tab)){write(output,"HTTP/1.1 409 Conflict\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");output.flush();return;}current=tab;
            write(output,"HTTP/1.1 200 OK\r\n"+cors+"Content-Type: text/event-stream; charset=utf-8\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n");output.flush();
            for(int event=1;!server.isClosed();event++){chunk(output,"data: "+event+"\n\n");Thread.sleep(1000);}
        }catch(Exception ignored){}finally{if(current!=null)active.remove(current);clients.remove(client);}
    }
    @Override public void close()throws IOException {server.close();for(Socket client:clients)try{client.close();}catch(IOException ignored){}workers.shutdownNow();}
}
