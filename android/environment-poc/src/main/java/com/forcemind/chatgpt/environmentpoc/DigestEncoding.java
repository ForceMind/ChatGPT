package com.forcemind.chatgpt.environmentpoc;

/** Fixed-width lowercase encoding; avoids one Formatter allocation per byte. */
final class DigestEncoding {
    private static final char[] HEX="0123456789abcdef".toCharArray();
    static String hex(byte[] bytes){
        char[] out=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++){int value=bytes[i]&255;out[i*2]=HEX[value>>>4];out[i*2+1]=HEX[value&15];}
        return new String(out);
    }
}
