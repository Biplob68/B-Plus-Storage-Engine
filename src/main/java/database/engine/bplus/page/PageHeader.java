package database.engine.bplus.page;

import java.nio.ByteBuffer;

/**
 * The fixed-size page header.
 *
 * <pre>
 *    0  1  pageType         0 = LEAF, 1 = INTERNAL
 *    1  1  flags            reserved, 0
 *    2  2  cellCount        live cells, == number of slots
 *    4  2  cellAreaStart    lowest byte any cell occupies; Page.SIZE when empty
 *    6  2  fragmentedBytes  dead bytes stranded above cellAreaStart by deletes
 *    8  4  rightSibling     page id, Page.NO_PAGE when absent
 *   12  4  reserved         0
 *   16  8  lsn              reserved for COW durability, 0
 * </pre>
 *
 */
final class PageHeader {

    static final int SIZE = 24;

    private static final int TYPE = 0;
    private static final int CELL_COUNT = 2;
    private static final int CELL_AREA_START = 4;
    private static final int FRAGMENTED_BYTES = 6;
    private static final int RIGHT_SIBLING = 8;

    private final ByteBuffer buffer;

    PageHeader(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    PageType type() {
        return PageType.fromCode(buffer.get(TYPE) & 0xFF);
    }

    void type(PageType type) {
        buffer.put(TYPE, (byte) type.code());
    }

    int cellCount() {
        return readUnsignedShort(CELL_COUNT);
    }

    void cellCount(int count) {
        writeUnsignedShort(CELL_COUNT, count);
    }

    int cellAreaStart() {
        return readUnsignedShort(CELL_AREA_START);
    }

    void cellAreaStart(int offset) {
        writeUnsignedShort(CELL_AREA_START, offset);
    }

    int fragmentedBytes() {
        return readUnsignedShort(FRAGMENTED_BYTES);
    }

    void fragmentedBytes(int bytes) {
        writeUnsignedShort(FRAGMENTED_BYTES, bytes);
    }

    int rightSibling() {
        return buffer.getInt(RIGHT_SIBLING);
    }

    void rightSibling(int pageId) {
        buffer.putInt(RIGHT_SIBLING, pageId);
    }

    private int readUnsignedShort(int offset) {
        return buffer.getShort(offset) & 0xFFFF;
    }

    private void writeUnsignedShort(int offset, int value) {
        buffer.putShort(offset, (short) value);
    }
}
