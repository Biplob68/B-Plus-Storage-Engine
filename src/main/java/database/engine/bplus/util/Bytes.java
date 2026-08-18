package database.engine.bplus.util;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;


public final class Bytes {

    private Bytes() {
        throw new AssertionError("no instances");
    }

    /**
     * Bytes compare as 0..255, and a prefix sorts before the longer array.
     */
    public static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    /**
     * Same ordering, comparing a buffer region against {@code other} so page search never copies
     * keys out. Negative when the stored bytes sort first.
     */
    public static int compare(ByteBuffer buf, int index, int length, byte[] other) {
        Objects.requireNonNull(buf, "buf");
        Objects.requireNonNull(other, "other");
        int n = Math.min(length, other.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(buf.get(index + i) & 0xFF, other[i] & 0xFF);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(length, other.length);
    }

    /**
     * Bytes needed to encode {@code value}; never more than 5.
     */
    public static int varIntSize(int value) {
        requireNonNegative(value);
        if (value < (1 << 7)) return 1;
        if (value < (1 << 14)) return 2;
        if (value < (1 << 21)) return 3;
        if (value < (1 << 28)) return 4;
        return 5;
    }

    /**
     * Writes {@code value} at {@code index}, seven payload bits per byte, and returns its width.
     */
    public static int putVarInt(ByteBuffer buf, int index, int value) {
        Objects.requireNonNull(buf, "buf");
        requireNonNegative(value);
        int i = index;
        while ((value & ~0x7F) != 0) {
            buf.put(i++, (byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put(i++, (byte) value);
        return i - index;
    }

    /**
     * Reads the varint at {@code index}. Pair with {@link #varIntSize} to find the next field.
     */
    public static int getVarInt(ByteBuffer buf, int index) {
        Objects.requireNonNull(buf, "buf");
        int result = 0;
        for (int i = 0, shift = 0; i < 5; i++, shift += 7) {
            int b = buf.get(index + i) & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0) {
                    throw new IllegalStateException("varint at " + index + " overflows a signed int");
                }
                return result;
            }
        }
        throw new IllegalStateException("malformed varint at " + index + ": no terminating byte");
    }

    private static void requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("varints encode non-negative values only, got " + value);
        }
    }
}
