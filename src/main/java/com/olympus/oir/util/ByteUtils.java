package com.olympus.oir.util;

import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for little-endian binary reading from OIR files.
 *
 * All OIR data is stored in little-endian byte order (spec section 8.1).
 *
 * OIR "String" format: 4-byte LE int (length) + UTF-8 bytes.
 * OIR "int" format:    4-byte LE signed integer.
 * OIR "XML" format:    same as String (length-prefixed).
 * OIR "Binary" format: same as String (length-prefixed).
 */
public final class ByteUtils {

    private ByteUtils() {} // utility class

    // ── RandomAccessFile read helpers ────────────────────────

    /** Read a 4-byte little-endian int32 from current RAF position. */
    public static int readInt32(RandomAccessFile raf) throws IOException {
        byte[] buf = new byte[4];
        raf.readFully(buf);
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Read a 4-byte little-endian int32 at a specific position. */
    public static int readInt32At(RandomAccessFile raf, long pos) throws IOException {
        raf.seek(pos);
        return readInt32(raf);
    }

    /** Read an 8-byte little-endian int64 from current RAF position. */
    public static long readInt64(RandomAccessFile raf) throws IOException {
        byte[] buf = new byte[8];
        raf.readFully(buf);
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /** Read an 8-byte little-endian int64 at a specific position. */
    public static long readInt64At(RandomAccessFile raf, long pos) throws IOException {
        raf.seek(pos);
        return readInt64(raf);
    }

    /**
     * Read an OIR-format String from current RAF position.
     * Format: [4B int length] [length bytes UTF-8]
     * Returns empty string if length == 0.
     */
    public static String readOirString(RandomAccessFile raf) throws IOException {
        int len = readInt32(raf);
        if (len <= 0) return "";
        byte[] buf = new byte[len];
        raf.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /**
     * Read an OIR-format Binary blob from current RAF position.
     * Format: [4B int length] [length bytes]
     * Returns empty array if length == 0.
     */
    public static byte[] readOirBinary(RandomAccessFile raf) throws IOException {
        int len = readInt32(raf);
        if (len <= 0) return new byte[0];
        byte[] buf = new byte[len];
        raf.readFully(buf);
        return buf;
    }

    /** Skip N bytes from current RAF position. */
    public static void skip(RandomAccessFile raf, int n) throws IOException {
        raf.skipBytes(n);
    }

    // ── ByteBuffer read helpers (for in-memory byte arrays) ──

    /** Wrap a byte array as a little-endian ByteBuffer. */
    public static ByteBuffer wrap(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Read 4-byte LE int from ByteBuffer at given position. */
    public static int readInt32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Read 8-byte LE long from ByteBuffer at given position. */
    public static long readInt64(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /**
     * Read an OIR string from a byte array at a given offset.
     * Returns a String[2]: [0]=the string, [1]=bytes consumed as String.
     */
    public static String readOirString(byte[] data, int offset, int[] outBytesRead) {
        int len = readInt32(data, offset);
        outBytesRead[0] = 4 + Math.max(0, len);
        if (len <= 0) return "";
        return new String(data, offset + 4, len, StandardCharsets.UTF_8);
    }

    // ── Write helpers (for CustomMetadataWriter) ─────────────

    /** Write a 4-byte LE int to a byte array at given position. */
    public static void writeInt32(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    /** Write an 8-byte LE long to a byte array at given position. */
    public static void writeInt64(byte[] data, int offset, long value) {
        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
    }

    /** Encode a string as OIR String format: [4B length] [UTF-8 bytes]. */
    public static byte[] encodeOirString(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[]{0, 0, 0, 0};
        }
        byte[] strBytes = s.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[4 + strBytes.length];
        writeInt32(result, 0, strBytes.length);
        System.arraycopy(strBytes, 0, result, 4, strBytes.length);
        return result;
    }

    // ── Formatting helpers ───────────────────────────────────

    /** Format a long value as hex string with "0x" prefix and minimum 8 hex digits. */
    public static String toHex(long value) {
        return String.format("0x%08X", value);
    }

    /** Format a long value as hex string with "0x" prefix and minimum 16 hex digits. */
    public static String toHex16(long value) {
        return String.format("0x%016X", value);
    }

    /**
     * Produce a hex dump of up to maxBytes bytes from data, formatted as:
     *   00000000: 4F 4C 59 4D 50 55 53 52  41 57 46 4F 52 4D 41 54  OLYMPUSRAWFORMAT
     */
    public static String hexDump(byte[] data, int maxBytes) {
        int len = Math.min(data.length, maxBytes);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i += 16) {
            sb.append(String.format("%08X: ", i));
            StringBuilder ascii = new StringBuilder();
            for (int j = i; j < Math.min(i + 16, len); j++) {
                sb.append(String.format("%02X ", data[j] & 0xFF));
                if (j == i + 7) sb.append(" ");
                char c = (char)(data[j] & 0xFF);
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            // Pad to align ASCII column
            int remaining = 16 - (Math.min(i + 16, len) - i);
            for (int p = 0; p < remaining; p++) sb.append("   ");
            if (remaining > 0 && (Math.min(i + 16, len) - i) <= 8) sb.append(" ");
            sb.append(" ").append(ascii).append("\n");
        }
        if (data.length > maxBytes) {
            sb.append(String.format("... (%d more bytes)\n", data.length - maxBytes));
        }
        return sb.toString();
    }
}
