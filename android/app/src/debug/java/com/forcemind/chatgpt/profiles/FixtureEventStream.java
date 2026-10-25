package com.forcemind.chatgpt.profiles;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Debug-only unbounded SSE input. Native reads may arrive on different worker threads. */
final class FixtureEventStream extends InputStream {
    private final long intervalNanos;private long deadline;private int sequence,offset;
    private byte[] frame=new byte[0];private boolean closed;
    FixtureEventStream(long intervalMillis){if(intervalMillis<1)throw new IllegalArgumentException();intervalNanos=intervalMillis*1000000L;}
    @Override public synchronized int read(byte[] target,int start,int length)throws IOException {
        if(start<0||length<0||start>target.length-length)throw new IndexOutOfBoundsException();if(length==0)return 0;
        while(!closed&&offset==frame.length){long remaining=deadline-System.nanoTime();if(remaining>0){try{wait(Math.max(1,remaining/1000000L));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Stream interrupted",e);}}else{frame=("data: "+(++sequence)+"\n\n").getBytes(StandardCharsets.UTF_8);offset=0;deadline=System.nanoTime()+intervalNanos;}}
        if(closed)return -1;int count=Math.min(length,frame.length-offset);System.arraycopy(frame,offset,target,start,count);offset+=count;return count;
    }
    @Override public int read()throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
    // InputStream's default available() == 0 means unknown/non-buffered length.
    @Override public synchronized void close(){closed=true;notifyAll();}
}
