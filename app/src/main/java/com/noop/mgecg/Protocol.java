package com.noop.mgecg;

import java.util.zip.CRC32;

public final class Protocol {

    public static final String SERVICE =
            "fd4b0001-cce1-4033-93ce-002d5875f58a";

    public static final String CMD_WRITE =
            "fd4b0002-cce1-4033-93ce-002d5875f58a";

    public static final String CMD_NOTIFY =
            "fd4b0003-cce1-4033-93ce-002d5875f58a";

    public static final String EVENT_NOTIFY =
            "fd4b0004-cce1-4033-93ce-002d5875f58a";

    public static final String DATA_NOTIFY =
            "fd4b0005-cce1-4033-93ce-002d5875f58a";

    public static final String EXTRA_NOTIFY =
            "fd4b0007-cce1-4033-93ce-002d5875f58a";

    private Protocol() {}

    /*
     * ------------------------------------------------------------
     * WHOOP client hello
     * ------------------------------------------------------------
     */

    public static byte[] clientHello() {
        return hex(
                "AA 01 08 00 00 01 E6 71 " +
                "23 01 91 01 36 3E 5C 8D"
        );
    }

    /*
     * ------------------------------------------------------------
     * Labrador command builder
     *
     * Existing wire format is preserved exactly.
     * ------------------------------------------------------------
     */

    public static byte[] labrador(
            int cmd,
            int arg,
            int seq) {

        return labrador(
                0x23,
                cmd,
                arg,
                seq);
    }

    public static byte[] labrador(
            int type,
            int cmd,
            int arg,
            int seq) {

        /*
         * Inner Labrador payload:
         *
         *     type
         *     sequence
         *     command
         *     0x01
         *     argument
         */

        byte[] inner = new byte[] {
                (byte) type,
                (byte) seq,
                (byte) cmd,
                0x01,
                (byte) arg
        };

        /*
         * Labrador payload CRC32.
         */
        CRC32 c = new CRC32();
        c.update(inner);

        long crc = c.getValue();

        /*
         * Envelope declared length includes
         * the inner payload plus its four CRC32 bytes.
         */
        int declared =
                inner.length + 4;

        byte[] out =
                new byte[8 + declared];

        /*
         * AA 01 envelope.
         */
        out[0] = (byte) 0xAA;
        out[1] = 0x01;

        /*
         * Little-endian declared length.
         */
        out[2] =
                (byte) (declared & 0xff);

        out[3] =
                (byte) ((declared >>> 8) & 0xff);

        /*
         * Fixed header.
         */
        out[4] = 0x00;
        out[5] = 0x01;

        /*
         * Header CRC16.
         */
        int h =
                crc16Modbus(
                        out,
                        0,
                        6);

        out[6] =
                (byte) (h & 0xff);

        out[7] =
                (byte) ((h >>> 8) & 0xff);

        /*
         * Inner payload.
         */
        System.arraycopy(
                inner,
                0,
                out,
                8,
                inner.length);

        /*
         * CRC32 immediately follows inner payload.
         * WHOOP uses little-endian representation here.
         */
        int p =
                8 + inner.length;

        out[p] =
                (byte) (crc & 0xff);

        out[p + 1] =
                (byte) ((crc >>> 8) & 0xff);

        out[p + 2] =
                (byte) ((crc >>> 16) & 0xff);

        out[p + 3] =
                (byte) ((crc >>> 24) & 0xff);

        return out;
    }

    /*
     * ------------------------------------------------------------
     * CRC16 MODBUS
     * ------------------------------------------------------------
     */

    public static int crc16Modbus(
            byte[] d,
            int off,
            int len) {

        int crc = 0xFFFF;

        for (
                int i = off;
                i < off + len;
                i++) {

            crc ^= d[i] & 0xff;

            for (int j = 0; j < 8; j++) {

                crc =
                        ((crc & 1) != 0)
                                ? ((crc >>> 1) ^ 0xA001)
                                : (crc >>> 1);
            }
        }

        return crc & 0xffff;
    }

    /*
     * ------------------------------------------------------------
     * Hex helpers
     * ------------------------------------------------------------
     */

    public static String hex(byte[] b) {

        StringBuilder s =
                new StringBuilder();

        for (byte x : b) {

            s.append(
                    String.format(
                            "%02X ",
                            x & 255));
        }

        return s.toString().trim();
    }

    public static byte[] hex(String s) {

        String[] p =
                s.trim().split("\\s+");

        byte[] b =
                new byte[p.length];

        for (int i = 0; i < p.length; i++) {

            b[i] =
                    (byte) Integer.parseInt(
                            p[i],
                            16);
        }

        return b;
    }

    /*
     * ------------------------------------------------------------
     * Safe frame summary
     * ------------------------------------------------------------
     */

    public static String frameSummary(
            byte[] b) {

        if (b == null ||
                b.length < 8) {

            return "short-frame";
        }

        if ((b[0] & 255) != 0xAA ||
                (b[1] & 255) != 0x01) {

            return "non-puffin/fragment";
        }

        int declared =
                (b[2] & 255) |
                ((b[3] & 255) << 8);

        int fixed0 =
                b[4] & 255;

        int fixed1 =
                b[5] & 255;

        int headerCrc =
                (b[6] & 255) |
                ((b[7] & 255) << 8);

        StringBuilder s =
                new StringBuilder();

        s.append(
                String.format(
                        "AA01 declared=%d " +
                        "fixed=%02X%02X " +
                        "hdrCRC=%04X",
                        declared,
                        fixed0,
                        fixed1,
                        headerCrc));

        /*
         * A normal command/response frame has
         * enough bytes to expose type/sequence/cmd.
         */
        if (b.length >= 11) {

            int type =
                    b[8] & 255;

            int seq =
                    b[9] & 255;

            int cmd =
                    b[10] & 255;

            s.append(
                    String.format(
                            " type=0x%02X " +
                            "seq=0x%02X " +
                            "cmd=0x%02X",
                            type,
                            seq,
                            cmd));
        }

        return s.toString();
    }

    /*
     * ------------------------------------------------------------
     * Detailed command-frame decoder
     * ------------------------------------------------------------
     */

    public static String decodeCommand(
            byte[] b) {

        if (b == null ||
                b.length < 12) {

            return "too-short";
        }

        if ((b[0] & 255) != 0xAA ||
                (b[1] & 255) != 0x01) {

            return "not-AA01";
        }

        int declared =
                (b[2] & 255) |
                ((b[3] & 255) << 8);

        int headerCrc =
                (b[6] & 255) |
                ((b[7] & 255) << 8);

        int calculatedHeaderCrc =
                crc16Modbus(
                        b,
                        0,
                        6);

        int type =
                b[8] & 255;

        int sequence =
                b[9] & 255;

        int command =
                b[10] & 255;

        int marker =
                b[11] & 255;

        StringBuilder s =
                new StringBuilder();

        s.append(
                String.format(
                        "DECLARED=%d ",
                        declared));

        s.append(
                String.format(
                        "TYPE=0x%02X ",
                        type));

        s.append(
                String.format(
                        "SEQ=0x%02X ",
                        sequence));

        s.append(
                String.format(
                        "CMD=0x%02X ",
                        command));

        s.append(
                String.format(
                        "MARKER=0x%02X ",
                        marker));

        s.append(
                String.format(
                        "HDRCRC=%04X ",
                        headerCrc));

        s.append(
                String.format(
                        "CALC=%04X ",
                        calculatedHeaderCrc));

        s.append(
                headerCrc == calculatedHeaderCrc
                        ? "HDRCRC_OK"
                        : "HDRCRC_BAD");

        /*
         * Expected Labrador layout:
         *
         *     8..12   inner payload
         *     13..16  CRC32
         */
        if (b.length >= 17) {

            long supplied =
                    (b[13] & 0xffL) |
                    ((b[14] & 0xffL) << 8) |
                    ((b[15] & 0xffL) << 16) |
                    ((b[16] & 0xffL) << 24);

            CRC32 c =
                    new CRC32();

            c.update(
                    b,
                    8,
                    5);

            long calculated =
                    c.getValue();

            s.append(
                    String.format(
                            " CRC32=%08X " +
                            "CALC=%08X",
                            supplied,
                            calculated));

            s.append(
                    supplied == calculated
                            ? " CRC32_OK"
                            : " CRC32_BAD");
        }

        return s.toString();
    }

    /*
     * ------------------------------------------------------------
     * Little-endian uint32
     * ------------------------------------------------------------
     */

    public static long u32le(
            byte[] b,
            int off) {

        return
                (b[off] & 0xffL)
                |
                ((b[off + 1] & 0xffL) << 8)
                |
                ((b[off + 2] & 0xffL) << 16)
                |
                ((b[off + 3] & 0xffL) << 24);
    }
}
