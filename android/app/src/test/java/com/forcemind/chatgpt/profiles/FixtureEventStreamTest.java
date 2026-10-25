package com.forcemind.chatgpt.profiles;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class FixtureEventStreamTest {
    @Test public void successiveNativeWorkerReadsReceiveDistinctFramesAndCloseUnblocks()throws Exception{
        FixtureEventStream stream=new FixtureEventStream(20);assertEquals(0,stream.available());
        for(int n=1;n<=3;n++){
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{String frame=worker.submit(()->{byte[] b=new byte[2048];int size=stream.read(b,0,b.length);return new String(b,0,size,StandardCharsets.UTF_8);}).get(2,TimeUnit.SECONDS);assertEquals("data: "+n+"\n\n",frame);}finally{worker.shutdownNow();}
        }
        FixtureEventStream pending=new FixtureEventStream(10000);pending.read(new byte[2048]);ExecutorService reader=Executors.newSingleThreadExecutor();
        try{Future<Integer> blocked=reader.submit(()->pending.read(new byte[2048]));pending.close();assertEquals(-1,(int)blocked.get(2,TimeUnit.SECONDS));assertEquals(-1,pending.read());}finally{reader.shutdownNow();stream.close();}
    }
}
