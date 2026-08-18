package database.engine.bplus.page;

import database.engine.bplus.util.Bytes;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlottedPageTest {

    private static SlottedPage newLeaf() {
        return SlottedPage.init(ByteBuffer.allocate(Page.SIZE), PageType.LEAF);
    }

    /** 4-byte big-endian, so ascending ints give ascending unsigned byte order. */
    private static byte[] key(int i) {
        return new byte[] {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i};
    }

    private static byte[] value(int i) {
        byte[] v = new byte[16];
        Arrays.fill(v, (byte) i);
        v[0] = (byte) (i >>> 8);
        return v;
    }

    private static void insert(SlottedPage page, byte[] k, byte[] v) {
        int idx = page.binarySearch(k);
        assertThat(idx).as("key must be absent").isNegative();
        page.insertCell(-idx - 1, k, v);
    }

    private static byte[] randomBytes(Random rnd, int length) {
        byte[] b = new byte[length];
        rnd.nextBytes(b);
        return b;
    }

    private static TreeMap<byte[], byte[]> newModel() {
        return new TreeMap<>(Arrays::compareUnsigned);
    }

    private static void assertSorted(SlottedPage page) {
        for (int i = 1; i < page.cellCount(); i++) {
            assertThat(Bytes.compare(page.key(i - 1), page.key(i)))
                    .as("slot %d must sort before slot %d", i - 1, i)
                    .isNegative();
        }
    }

    private static void assertMatches(SlottedPage page, TreeMap<byte[], byte[]> model) {
        assertThat(page.cellCount()).isEqualTo(model.size());
        assertSorted(page);
        int slot = 0;
        for (Map.Entry<byte[], byte[]> entry : model.entrySet()) {
            assertThat(page.key(slot)).containsExactly(entry.getKey());
            assertThat(page.value(slot)).containsExactly(entry.getValue());
            assertThat(page.binarySearch(entry.getKey())).isEqualTo(slot);
            slot++;
        }
    }


    @Test
    void freshPageIsEmptyAndOffersTheWholeUsableArea() {
        SlottedPage page = newLeaf();

        assertThat(page.type()).isEqualTo(PageType.LEAF);
        assertThat(page.cellCount()).isZero();
        assertThat(page.rightSibling()).isEqualTo(Page.NO_PAGE);
        assertThat(page.freeSpace()).isEqualTo(SlottedPage.USABLE_BYTES);
        assertThat(page.binarySearch(key(1))).isEqualTo(-1);
    }

    @Test
    void rejectsBuffersThatAreNotOnePage() {
        assertThatThrownBy(() -> SlottedPage.init(ByteBuffer.allocate(Page.SIZE - 1), PageType.LEAF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlottedPage.init(ByteBuffer.allocate(Page.SIZE).asReadOnlyBuffer(), PageType.LEAF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrapReadsBackAPageWrittenThroughAnotherView() {
        ByteBuffer raw = ByteBuffer.allocate(Page.SIZE);
        SlottedPage page = SlottedPage.init(raw, PageType.INTERNAL);
        page.setRightSibling(7);
        page.insertCell(0, key(1), value(1));

        SlottedPage reopened = SlottedPage.wrap(raw);
        assertThat(reopened.type()).isEqualTo(PageType.INTERNAL);
        assertThat(reopened.rightSibling()).isEqualTo(7);
        assertThat(reopened.cellCount()).isEqualTo(1);
        assertThat(reopened.key(0)).containsExactly(key(1));
        assertThat(reopened.value(0)).containsExactly(value(1));
    }

    @Test
    void initDoesNotDisturbTheCallersBufferPosition() {
        ByteBuffer raw = ByteBuffer.allocate(Page.SIZE);
        raw.position(100).limit(200);
        SlottedPage page = SlottedPage.init(raw, PageType.LEAF);
        page.insertCell(0, key(1), value(1));

        assertThat(raw.position()).isEqualTo(100);
        assertThat(raw.limit()).isEqualTo(200);
    }

    @Test
    void binarySearchReportsInsertionPointsForMissingKeys() {
        SlottedPage page = newLeaf();
        for (int i : new int[] {10, 20, 30}) {
            insert(page, key(i), value(i));
        }

        assertThat(page.binarySearch(key(20))).isEqualTo(1);
        assertThat(page.binarySearch(key(5))).isEqualTo(-1);
        assertThat(page.binarySearch(key(15))).isEqualTo(-2);
        assertThat(page.binarySearch(key(35))).isEqualTo(-4);
    }

    @Test
    void insertRejectsKeysThatWouldBreakSortOrder() {
        SlottedPage page = newLeaf();
        page.insertCell(0, key(10), value(10));
        page.insertCell(1, key(20), value(20));

        assertThatThrownBy(() -> page.insertCell(0, key(30), value(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> page.insertCell(2, key(5), value(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> page.insertCell(1, key(10), value(10)))
                .as("duplicates")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedCellsAreRefusedRatherThanCollapsingFanout() {
        SlottedPage page = newLeaf();
        byte[] huge = new byte[SlottedPage.MAX_CELL_SIZE];

        assertThat(page.hasSpaceFor(4, huge.length)).isFalse();
        assertThatThrownBy(() -> page.insertCell(0, key(1), huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow page");

        // Largest permitted cell, found by search because the valLen varint grows with the value.
        int valLen = SlottedPage.MAX_CELL_SIZE;
        while (SlottedPage.cellSize(4, valLen) > SlottedPage.MAX_CELL_SIZE) {
            valLen--;
        }
        assertThat(SlottedPage.cellSize(4, valLen)).isEqualTo(SlottedPage.MAX_CELL_SIZE);
        for (int i = 0; i < 4; i++) {
            assertThat(page.hasSpaceFor(4, valLen)).as("cell %d of 4", i).isTrue();
            page.insertCell(i, key(i), new byte[valLen]);
        }
        assertThat(page.cellCount()).isEqualTo(4);
    }

    @Test
    void emptyKeysAndValuesAreLegal() {
        SlottedPage page = newLeaf();
        page.insertCell(0, new byte[0], new byte[0]);

        assertThat(page.key(0)).isEmpty();
        assertThat(page.value(0)).isEmpty();
        assertThat(page.binarySearch(new byte[0])).isZero();
    }

    // -------------------------- fill, delete, compact -------------------------------

    @Test
    void fillDeleteEverySecondCompactAndReadBack() {
        SlottedPage page = newLeaf();

        // Scrambled order so mid-array slot shifting is exercised, not just appends.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(42));

        TreeMap<byte[], byte[]> model = newModel();
        for (int i : order) {
            byte[] k = key(i);
            byte[] v = value(i);
            if (!page.hasSpaceFor(k.length, v.length)) {
                break; // full: every cell here is the same size
            }
            insert(page, k, v);
            model.put(k, v);
        }

        int fullCount = page.cellCount();
        assertThat(fullCount).isEqualTo(model.size()).isGreaterThan(100);
        assertThat(page.hasSpaceFor(4, 16)).isFalse();
        assertSorted(page);

        // Delete every second cell, downward so surviving indices stay valid.
        int freeBeforeDeletes = page.freeSpace();
        int reclaimed = 0;
        int firstOdd = (fullCount % 2 == 0) ? fullCount - 1 : fullCount - 2;
        for (int i = firstOdd; i >= 1; i -= 2) {
            byte[] k = page.key(i);
            reclaimed += SlottedPage.cellSize(k.length, page.value(i).length) + SlotDirectory.SLOT_SIZE;
            page.deleteCell(i);
            model.remove(k);
        }
        assertThat(page.freeSpace())
                .as("every deleted byte accounted for, fragmented or not")
                .isEqualTo(freeBeforeDeletes + reclaimed);

        int freeBeforeCompact = page.freeSpace();
        page.compact();
        assertThat(page.freeSpace()).as("compaction moves space, never creates it").isEqualTo(freeBeforeCompact);
        assertMatches(page, model);

        // The reclaimed space is genuinely usable again.
        int refilled = 0;
        for (int i = 4000; page.hasSpaceFor(4, 16); i++) {
            insert(page, key(i), value(i));
            refilled++;
        }
        assertThat(refilled).isGreaterThan(fullCount / 3);
        assertSorted(page);
    }

    // ----------------------------- randomized  -------------------------------

    @Test
    void randomOperationsAgreeWithTreeMap() {
        Random rnd = new Random(20_260_815L);
        SlottedPage page = newLeaf();
        TreeMap<byte[], byte[]> model = newModel();

        for (int op = 0; op < 200; op++) {
            if (rnd.nextInt(4) == 0 && page.cellCount() > 0) {
                int victim = rnd.nextInt(page.cellCount());
                model.remove(page.key(victim));
                page.deleteCell(victim);
            } else {
                byte[] k = randomBytes(rnd, 1 + rnd.nextInt(8));
                byte[] v = randomBytes(rnd, rnd.nextInt(9));

                int idx = page.binarySearch(k);
                if (idx >= 0) { // update == delete then insert
                    page.deleteCell(idx);
                    model.remove(k);
                    idx = page.binarySearch(k);
                }
                if (!page.hasSpaceFor(k.length, v.length)) {
                    continue;
                }
                page.insertCell(-idx - 1, k, v);
                model.put(k, v);
            }

            if (op % 50 == 49) {
                page.compact();
            }
            assertThat(page.cellCount()).as("after op %d", op).isEqualTo(model.size());
        }

        assertThat(page.cellCount()).isGreaterThan(50);
        assertMatches(page, model);

        for (int i = 0; i < 200; i++) {
            byte[] probe = randomBytes(rnd, 9); // longer than any inserted key
            assertThat(page.binarySearch(probe)).isNegative();
        }
    }
}
