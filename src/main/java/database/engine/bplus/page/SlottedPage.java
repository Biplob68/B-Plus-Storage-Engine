package database.engine.bplus.page;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * A page of variable-length key/value pairs held in sorted order.
 *
 * <p>Sorted 2-byte slots grow forward from the header, cells grow backward from the end, and free
 * space is whatever lies between. Inserting shifts slots, never payload. The slot array is the
 * page's only ordering guarantee; the cell area is unordered bytes.
 *
 * <pre>
 *   0                                                                          Page.SIZE
 *   +--------+--------------+-------------------+-------------------------------+
 *   | header |   slots -&gt;   |    free space     |            &lt;- cells           |
 *   |  24 B  | 2 B per cell |                   |                               |
 *   +--------+--------------+-------------------+-------------------------------+
 *            24      24 + 2*cellCount     cellAreaStart
 *
 *   freeSpace = (cellAreaStart - slot array end) + fragmentedBytes
 *   invariant   header + slots + free + liveCellBytes + fragmentedBytes == Page.SIZE
 * </pre>
 */
public final class SlottedPage {

    public static final int USABLE_BYTES = Page.SIZE - PageHeader.SIZE;

    public static final int SLOT_SIZE = SlotDirectory.SLOT_SIZE;

    public static final int MAX_CELL_SIZE = USABLE_BYTES / 4 - SLOT_SIZE;

    private static final byte[] EMPTY_PAGE = new byte[Page.SIZE];

    private final ByteBuffer buffer;
    private final PageHeader header;
    private final SlotDirectory slots;

    private SlottedPage(ByteBuffer buffer) {
        this.buffer = buffer;
        this.header = new PageHeader(buffer);
        this.slots = new SlotDirectory(buffer, header);
    }

    // ------------------------- lifecycle -------------------------------------------


    public static SlottedPage init(ByteBuffer buffer, PageType type) {
        SlottedPage page = new SlottedPage(pageView(buffer));
        page.reset(type);
        return page;
    }


    public void reset(PageType type) {
        Objects.requireNonNull(type, "type");
        buffer.put(0, EMPTY_PAGE, 0, Page.SIZE);
        header.type(type);
        header.cellAreaStart(Page.SIZE); // every other header field is zero
    }

    /**
     * Opens a buffer that already holds a formatted page, leaving its bytes untouched.
     */
    public static SlottedPage wrap(ByteBuffer buffer) {
        SlottedPage page = new SlottedPage(pageView(buffer));
        page.type(); // fail fast when this is not a formatted page
        return page;
    }

    private static ByteBuffer pageView(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.isReadOnly()) {
            throw new IllegalArgumentException("page buffer must be writable");
        }
        if (buffer.capacity() != Page.SIZE) {
            throw new IllegalArgumentException("capacity must be Page.SIZE, got " + buffer.capacity());
        }
        // clear(): absolute get/put bounds-check against the limit, not the capacity
        return buffer.duplicate().clear().order(ByteOrder.BIG_ENDIAN);
    }

    //---------------------------- page state -----------------------------------

    public PageType type() {
        return header.type();
    }

    public int cellCount() {
        return slots.size();
    }

    /**
     * Next leaf in key order, or {@link Page#NO_PAGE}.
     */
    public int rightSibling() {
        return header.rightSibling();
    }

    public void setRightSibling(int pageId) {
        header.rightSibling(pageId);
    }

    /**
     * Reclaimable bytes: the gap plus dead bytes.
     */
    public int freeSpace() {
        return contiguousFreeSpace() + header.fragmentedBytes();
    }

    /**
     * The single run of free bytes between the slot array and the cell area.
     */
    private int contiguousFreeSpace() {
        return header.cellAreaStart() - slots.endOffset();
    }

    // ------------------------ reading ----------------------------------

    public int binarySearch(byte[] key) {
        Objects.requireNonNull(key, "key");
        int low = 0;
        int high = slots.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = compareStoredKey(middle, key);
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public byte[] key(int slotIndex) {
        return CellCodec.readKey(buffer, slots.cellOffset(slotIndex));
    }

    public byte[] value(int slotIndex) {
        return CellCodec.readValue(buffer, slots.cellOffset(slotIndex));
    }

    // ------------------------------ writing ------------------------------------

    public static int cellSize(int keyLength, int valueLength) {
        return CellCodec.size(keyLength, valueLength);
    }

    public static int entrySize(int keyLength, int valueLength) {
        return cellSize(keyLength, valueLength) + SLOT_SIZE;
    }

    public boolean hasSpaceFor(int keyLength, int valueLength) {
        if (keyLength < 0 || valueLength < 0) {
            throw new IllegalArgumentException("negative length: " + keyLength + "/" + valueLength);
        }
        int cellBytes = CellCodec.size(keyLength, valueLength);
        return cellBytes <= MAX_CELL_SIZE && cellBytes + SLOT_SIZE <= freeSpace();
    }


    public void insertCell(int slotIndex, byte[] key, byte[] value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        int count = slots.size();
        if (slotIndex < 0 || slotIndex > count) {
            throw new IndexOutOfBoundsException("slotIndex " + slotIndex + " out of range [0, " + count + "]");
        }
        int cellBytes = CellCodec.size(key.length, value.length);
        if (cellBytes > MAX_CELL_SIZE) {
            throw new IllegalArgumentException("cell of " + cellBytes + " bytes exceeds MAX_CELL_SIZE "
                    + MAX_CELL_SIZE + "; needs an overflow page");
        }
        int requiredBytes = cellBytes + SLOT_SIZE;
        if (requiredBytes > freeSpace()) {
            throw new IllegalStateException("page full: need " + requiredBytes + " bytes, have " + freeSpace());
        }
        requireSortOrder(slotIndex, key);

        if (requiredBytes > contiguousFreeSpace()) {
            compact();
        }
        int cellOffset = header.cellAreaStart() - cellBytes;
        CellCodec.write(buffer, cellOffset, key, value);
        slots.insert(slotIndex, cellOffset);
        header.cellAreaStart(cellOffset);
    }

    public void deleteCell(int slotIndex) {
        int cellOffset = slots.cellOffset(slotIndex);
        int cellBytes = CellCodec.byteLength(buffer, cellOffset);

        slots.remove(slotIndex);

        if (slots.size() == 0) {
            resetCellArea();
        } else if (cellOffset == header.cellAreaStart()) {
            header.cellAreaStart(cellOffset + cellBytes);
        } else {
            header.fragmentedBytes(header.fragmentedBytes() + cellBytes);
        }
    }

    /**
     * Slides live cells together so all free space becomes one run. Never changes
     * {@link #freeSpace()} — it only makes existing space usable.
     */
    public void compact() {
        int count = slots.size();
        if (count == 0) {
            resetCellArea();
            return;
        }

        int liveBytes = 0;
        for (int i = 0; i < count; i++) {
            liveBytes += CellCodec.byteLength(buffer, slots.cellOffset(i));
        }

        byte[] scratch = new byte[liveBytes];
        int newCellAreaStart = Page.SIZE - liveBytes;
        int packed = 0;
        for (int i = 0; i < count; i++) {
            int cellOffset = slots.cellOffset(i);
            int cellBytes = CellCodec.byteLength(buffer, cellOffset);
            buffer.get(cellOffset, scratch, packed, cellBytes);
            slots.cellOffset(i, newCellAreaStart + packed);
            packed += cellBytes;
        }
        buffer.put(newCellAreaStart, scratch, 0, liveBytes);

        header.cellAreaStart(newCellAreaStart);
        header.fragmentedBytes(0);
    }


    private void resetCellArea() {
        header.cellAreaStart(Page.SIZE);
        header.fragmentedBytes(0);
    }

    private int compareStoredKey(int slotIndex, byte[] key) {
        return CellCodec.compareKey(buffer, slots.cellOffset(slotIndex), key);
    }

    private void requireSortOrder(int slotIndex, byte[] key) {
        if (slotIndex > 0 && compareStoredKey(slotIndex - 1, key) >= 0) {
            throw new IllegalArgumentException("key must sort after the key in slot " + (slotIndex - 1));
        }
        if (slotIndex < slots.size() && compareStoredKey(slotIndex, key) <= 0) {
            throw new IllegalArgumentException("key must sort before the key in slot " + slotIndex);
        }
    }
}
