package com.noop.mgecg;

import java.util.zip.CRC32;

public final class Protocol {
    public static final String SERVICE = "fd4b0001-cce1-4033-93ce-002d5875f58a";
    public static final String CMD_WRITE = "fd4b0002-cce1-4033-93ce-002d5875f58a";
    public static final String CMD_NOTIFY = "fd4b0003-cce1-4033-93ce-002d5875f58a";
    public static final String EVENT_NOTIFY = "fd4b0004-cce1-4033-93ce-002d5875f58a";
    public static final String DATA_NOTIFY = "fd4b0005-cce1-4033-93ce-002d5875f58a";
    public static final String EXTRA_NOTIFY = "fd4b0007-cce1-4033-93ce-002d5875f58a";
    private Protocol() {}

    public static byte[] clientHello() { return hex("AA 01 08 00 00 01 E6 71 23 01 91 01 36 3E 5C 8D"); }

    public static byte[] labrador(int cmd, int arg, int seq) { return labrador(0x23, cmd, arg, seq); }

    public static byte[] labrador(int type, int cmd, int arg, int seq) {
        byte[] inner = new byte[] { (byte)type, (byte)seq, (byte)cmd, 0x01, (byte)arg };
        CRC32 c = new CRC32(); c.update(inner);
        long crc = c.getValue();
        int declared = inner.length + 4;
        byte[] out = new byte[8 + declared];
        out[0] = (byte)0xAA; out[1] = 0x01;
        out[2] = (byte)(declared & 0xff); out[3] = (byte)((declared >>> 8) & 0xff);
        out[4] = 0x00; out[5] = 0x01;
        int h = crc16Modbus(out, 0, 6);
        out[6] = (byte)(h & 0xff); out[7] = (byte)((h >>> 8) & 0xff);
        System.arraycopy(inner, 0, out, 8, inner.length);
        int p = 8 + inner.length;
        out[p] = (byte)(crc & 0xff); out[p+1] = (byte)((crc >>> 8) & 0xff);
        out[p+2] = (byte)((crc >>> 16) & 0xff); out[p+3] = (byte)((crc >>> 24) & 0xff);
        return out;
    }

    public static int crc16Modbus(byte[] d, int off, int len) {
        int crc=0xFFFF;
        for(int i=off;i<off+len;i++) { crc ^= d[i]&0xff; for(int j=0;j<8;j++) crc=((crc&1)!=0)?((crc>>>1)^0xA001):(crc>>>1); }
        return crc & 0xffff;
    }
    public static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte x:b) s.append(String.format("%02X ",x&255)); return s.toString().trim(); }
    public static byte[] hex(String s) { String[] p=s.trim().split("\\s+"); byte[] b=new byte[p.length]; for(int i=0;i<p.length;i++) b[i]=(byte)Integer.parseInt(p[i],16); return b; }
    public static String frameSummary(byte[] b) {
        if(b.length<12 || (b[0]&255)!=0xAA) return "non-puffin/fragment";
        int type=b[8]&255, seq=b[9]&255, cmd=b[10]&255;
        return String.format("type=%d seq=%d cmd=0x%02X",type,seq,cmd);
    }
    public static long u32le(byte[] b, int off) {
        return (b[off]&0xffL) | ((b[off+1]&0xffL)<<8) | ((b[off+2]&0xffL)<<16) | ((b[off+3]&0xffL)<<24);
    }
}
