package database.engine.bplus.page;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * The sorted array of cell pointers that gives a page its ordering.
 *
 * <p>Slot {@code i} sits at {@code PageHeader.SIZE + 2*i} and holds one unsigned short.
 *
 * <p>The directory owns its own length, which physically lives in {@link PageHeader#cellCount()}.
 */
final class SlotDirectory {

    static final int SLOT_SIZE = 2;

    private final ByteBuffer buffer;
    private final PageHeader header;

    SlotDirectory(ByteBuffer buffer, PageHeader header) {
        this.buffer = buffer;
        this.header = header;
    }

    int size() {
        return header.cellCount();
    }

    /** First byte past the last slot, which is where the free space begins. */
    int endOffset() {
        return PageHeader.SIZE + size() * SLOT_SIZE;
    }

    int cellOffset(int slotIndex) {
        Objects.checkIndex(slotIndex, size());
        return readSlot(slotIndex);
    }

    void cellOffset(int slotIndex, int cellOffset) {
        Objects.checkIndex(slotIndex, size());
        writeSlot(slotIndex, cellOffset);
    }

    /** Adds a slot at {@code slotIndex}, shifting the rest up one place. */
    void insert(int slotIndex, int cellOffset) {
        int count = size();
        for (int i = count; i > slotIndex; i--) { // highest first, so nothing is overwritten early
            writeSlot(i, readSlot(i - 1));
        }
        writeSlot(slotIndex, cellOffset);
        header.cellCount(count + 1);
    }

    /** Drops the slot at {@code slotIndex}, shifting the rest down one place. */
    void remove(int slotIndex) {
        int count = size();
        for (int i = slotIndex; i < count - 1; i++) {
            writeSlot(i, readSlot(i + 1));
        }
        header.cellCount(count - 1);
    }

    private int readSlot(int slotIndex) {
        return buffer.getShort(positionOf(slotIndex)) & 0xFFFF;
    }

    private void writeSlot(int slotIndex, int cellOffset) {
        buffer.putShort(positionOf(slotIndex), (short) cellOffset);
    }

    private static int positionOf(int slotIndex) {
        return PageHeader.SIZE + slotIndex * SLOT_SIZE;
    }
}
