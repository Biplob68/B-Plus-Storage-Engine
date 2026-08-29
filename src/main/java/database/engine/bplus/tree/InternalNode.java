package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.util.Bytes;

import java.util.Objects;

/**
 * Reads and writes an internal page: separators and the child pointers they route to.
 *
 * <pre>
 *   slot 0   ""   -> c0      leftmost child
 *   slot 1   20   -> c1      keys 20..39 go here
 *   slot 2   40   -> c2      keys 40..59 go here
 *   slot 3   60   -> c3      keys 60 and up go here
 * </pre>
 *
 * <p>Each cell's value is the 4-byte child page id.
 */
final class InternalNode {


    private static final byte[] LEFTMOST_KEY = new byte[0];

    private static final int CHILD_ID_BYTES = 4;

    private final SlottedPage page;

    InternalNode(SlottedPage page) {
        Objects.requireNonNull(page, "page");
        if (page.type() != PageType.INTERNAL) {
            throw new IllegalArgumentException("not an internal page: " + page.type());
        }
        this.page = page;
    }

    int childCount() {
        return page.cellCount();
    }

    int childAt(int slotIndex) {
        return Bytes.decodeInt(page.value(slotIndex));
    }

    byte[] separatorAt(int slotIndex) {
        return page.key(slotIndex);
    }

    private int findChildSlot(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (page.cellCount() == 0) {
            throw new IllegalStateException("internal page has no children");
        }
        int index = page.binarySearch(key);
        int slotIndex = index >= 0 ? index : (-index - 1) - 1;
        if (slotIndex < 0) {
            throw new IllegalStateException("internal page is missing its leftmost child in slot 0");
        }
        return slotIndex;
    }


    int findChild(byte[] key) {
        return childAt(findChildSlot(key));
    }

    void setLeftmostChild(int childPageId) {
        if (hasLeftmostChild()) {
            page.deleteCell(0); // insertCell rejects a duplicate key, so the old one goes first
        }
        page.insertCell(0, LEFTMOST_KEY, Bytes.encodeInt(childPageId));
    }

    private boolean hasLeftmostChild() {
        return page.cellCount() > 0 && page.key(0).length == 0;
    }


    void insertSeparator(byte[] key, int childPageId) {
        Objects.requireNonNull(key, "key");
        if (key.length == 0) {
            throw new IllegalArgumentException("an empty key is the leftmost child, not a separator");
        }
        int index = page.binarySearch(key);
        if (index >= 0) {
            throw new IllegalArgumentException("separator already present in slot " + index);
        }
        page.insertCell(-index - 1, key, Bytes.encodeInt(childPageId));
    }

    void clear() {
        for (int i = page.cellCount() - 1; i >= 0; i--) {
            page.deleteCell(i);
        }
    }

    boolean hasSpaceForSeparator(int keyLength) {
        return page.hasSpaceFor(keyLength, CHILD_ID_BYTES);
    }
}
