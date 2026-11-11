package com.forcemind.chatgpt.profiles;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LoopbackFixtureServerTest {
    @Test public void actualSocketStreamsDistinctSseFramesAndRejectsWrongToken()throws Exception{
        String token=UUID.randomUUID().toString(),tab=UUID.randomUUID().toString();
        try(LoopbackFixtureServer server=new LoopbackFixtureServer(token,Collections.singleton(tab))){
            URL url=new URL(server.url(tab));assertTrue(server.matches(url.toString(),tab));assertFalse(server.matches(url.toString(),UUID.randomUUID().toString()));
            try(Socket socket=new Socket("127.0.0.1",url.getPort())){
                socket.setSoTimeout(5000);socket.getOutputStream().write(("GET "+url.getFile()+" HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://chatgpt.com\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));assertEquals("HTTP/1.1 200 OK",reader.readLine());String line;while(!(line=reader.readLine()).isEmpty())assertFalse(line.toLowerCase(Locale.ROOT).startsWith("content-length:"));
                for(int n=1;n<=3;n++){assertEquals(9,Integer.parseInt(reader.readLine(),16));assertEquals("data: "+n,reader.readLine());assertEquals("",reader.readLine());assertEquals("",reader.readLine());}
            }
            try(Socket socket=new Socket("127.0.0.1",url.getPort())){socket.setSoTimeout(5000);socket.getOutputStream().write(("GET "+url.getFile().replace(token,"invalid")+" HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://chatgpt.com\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();assertEquals("HTTP/1.1 403 Forbidden",new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine());}
        }
    }
}
