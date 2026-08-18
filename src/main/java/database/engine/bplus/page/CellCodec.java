package database.engine.bplus.page;

import database.engine.bplus.util.Bytes;

import java.nio.ByteBuffer;

/**
 * The cell byte format: {@code [varint keyLength][varint valueLength][key bytes][value bytes]}.
 *
 * <p>Stateless on purpose. A cell is a layout, not an object.
 */
final class CellCodec {

    private CellCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * Bytes a cell with these lengths occupies, excluding its slot.
     */
    static int size(int keyLength, int valueLength) {
        return Bytes.varIntSize(keyLength) + Bytes.varIntSize(valueLength) + keyLength + valueLength;
    }

    static void write(ByteBuffer buffer, int cellOffset, byte[] key, byte[] value) {
        int offset = cellOffset;
        offset += Bytes.putVarInt(buffer, offset, key.length);
        offset += Bytes.putVarInt(buffer, offset, value.length);
        buffer.put(offset, key);
        buffer.put(offset + key.length, value);
    }

    static int keyLength(ByteBuffer buffer, int cellOffset) {
        return Bytes.getVarInt(buffer, cellOffset);
    }

    static int valueLength(ByteBuffer buffer, int cellOffset) {
        return Bytes.getVarInt(buffer, valueLengthOffset(buffer, cellOffset));
    }

    /**
     * Total bytes this cell occupies.
     */
    static int byteLength(ByteBuffer buffer, int cellOffset) {
        return size(keyLength(buffer, cellOffset), valueLength(buffer, cellOffset));
    }

    static byte[] readKey(ByteBuffer buffer, int cellOffset) {
        byte[] key = new byte[keyLength(buffer, cellOffset)];
        buffer.get(keyOffset(buffer, cellOffset), key);
        return key;
    }

    static byte[] readValue(ByteBuffer buffer, int cellOffset) {
        byte[] value = new byte[valueLength(buffer, cellOffset)];
        buffer.get(keyOffset(buffer, cellOffset) + keyLength(buffer, cellOffset), value);
        return value;
    }

    /**
     * Negative when this cell's key sorts before {@code key}. Compares in place, copying nothing.
     */
    static int compareKey(ByteBuffer buffer, int cellOffset, byte[] key) {
        return Bytes.compare(buffer, keyOffset(buffer, cellOffset), keyLength(buffer, cellOffset), key);
    }

    private static int valueLengthOffset(ByteBuffer buffer, int cellOffset) {
        return cellOffset + Bytes.varIntSize(keyLength(buffer, cellOffset));
    }

    private static int keyOffset(ByteBuffer buffer, int cellOffset) {
        int lengthsEnd = valueLengthOffset(buffer, cellOffset);
        return lengthsEnd + Bytes.varIntSize(Bytes.getVarInt(buffer, lengthsEnd));
    }
}
