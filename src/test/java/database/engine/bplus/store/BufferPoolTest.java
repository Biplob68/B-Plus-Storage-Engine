package database.engine.bplus.store;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BufferPoolTest {

    private static final int SMALL_POOL = 8;

    @TempDir
    Path directory;

    private Pager pager() {
        return Pager.open(directory.resolve("test.db"));
    }

    private static byte[] key(int n) {
        return new byte[]{(byte) (n >>> 8), (byte) n};
    }

    private static SlottedPage pageFromFile(Pager pager, int pageId) {
        ByteBuffer buffer = ByteBuffer.allocate(Page.SIZE);
        pager.readPage(pageId, buffer);
        return SlottedPage.wrap(buffer);
    }

    private static void fillPool(BufferPool pool, int pageCount) {
        for (int i = 0; i < pageCount; i++) {
            pool.allocate(PageType.LEAF);
        }
    }

    private static int allocateWith(BufferPool pool, PageType type, byte[] key, byte[] value) {
        int pageId = pool.allocate(type);
        SlottedPage page = pool.get(pageId);
        try {
            page.insertCell(0, key, value);
        } finally {
            pool.release(pageId);
        }
        return pageId;
    }

    @Test
    void allocateFormatsThePageAndKeepsItInThePool() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);

            int pageId = pool.allocate(PageType.LEAF);

            assertThat(pool.isResident(pageId)).isTrue();
            assertThat(pool.pinCountOf(pageId)).as("allocate hands back an unpinned page").isZero();
            assertThat(pool.isDirty(pageId)).as("it exists only in memory so far").isTrue();

            SlottedPage page = pool.get(pageId);
            assertThat(page.type()).isEqualTo(PageType.LEAF);
            assertThat(page.freeSpace()).isEqualTo(SlottedPage.USABLE_BYTES);
            pool.release(pageId);
        }
    }

    @Test
    void allocatedIdsComeFromThePagerAndSkipTheMetaAndRootPages() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);

            assertThat(pool.allocate(PageType.LEAF)).isEqualTo(2);
            assertThat(pool.allocate(PageType.INTERNAL)).isEqualTo(3);
        }
    }

    @Test
    void aSecondGetIsServedFromTheCacheRatherThanTheFile() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = allocateWith(pool, PageType.LEAF, key(10), key(99));

            SlottedPage first = pool.get(pageId);
            SlottedPage second = pool.get(pageId);
            try {
                assertThat(first.key(0)).containsExactly(key(10));
                assertThat(second.key(0)).containsExactly(key(10));
                assertThat(pool.pinCountOf(pageId)).as("two borrows, two pins").isEqualTo(2);
            } finally {
                pool.release(pageId);
                pool.release(pageId);
            }
            assertThat(pool.pinCountOf(pageId)).isZero();
        }
    }

    @Test
    void bothCallersSeeTheSamePageBecauseTheyShareTheFrame() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = pool.allocate(PageType.LEAF);

            SlottedPage writer = pool.get(pageId);
            SlottedPage reader = pool.get(pageId);
            try {
                writer.insertCell(0, key(5), key(50));
                assertThat(reader.cellCount()).as("one frame, so one copy of the page").isEqualTo(1);
                assertThat(reader.key(0)).containsExactly(key(5));
            } finally {
                pool.release(pageId);
                pool.release(pageId);
            }
        }
    }

    @Test
    void aPageThatIsNotCachedIsReadFromTheFile() {
        Path file = directory.resolve("test.db");
        int pageId;

        try (Pager pager = Pager.open(file)) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            pageId = allocateWith(pool, PageType.LEAF, key(7), key(70));
            pool.flush();
        }

        try (Pager reopened = Pager.open(file)) {
            BufferPool pool = BufferPool.of(reopened, SMALL_POOL);
            assertThat(pool.isResident(pageId)).isFalse();

            SlottedPage page = pool.get(pageId);
            try {
                assertThat(page.key(0)).containsExactly(key(7));
                assertThat(pool.isResident(pageId)).isTrue();
            } finally {
                pool.release(pageId);
            }
        }
    }

    @Test
    void releasingMarksThePageChanged() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = allocateWith(pool, PageType.LEAF, key(1), key(1));
            pool.flush();
            assertThat(pool.isDirty(pageId)).isFalse();

            pool.get(pageId);
            pool.release(pageId);

            assertThat(pool.isDirty(pageId))
                    .as("get hands out a writable page, so the pool has to assume the worst")
                    .isTrue();
        }
    }

    @Test
    void theFileIsNotTouchedUntilFlush() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = allocateWith(pool, PageType.LEAF, key(4), key(40));

            SlottedPage beforeFlush = pageFromFile(pager, pageId);
            assertThat(beforeFlush.cellCount()).as("the file still holds the zeroed page").isZero();
            assertThat(beforeFlush.freeSpace()).isNotEqualTo(SlottedPage.USABLE_BYTES);

            pool.flush();

            SlottedPage afterFlush = pageFromFile(pager, pageId);
            assertThat(afterFlush.cellCount()).isEqualTo(1);
            assertThat(afterFlush.key(0)).containsExactly(key(4));
            assertThat(afterFlush.value(0)).containsExactly(key(40));
        }
    }

    @Test
    void flushWritesEveryChangedPageAndLeavesThemClean() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int first = allocateWith(pool, PageType.LEAF, key(1), key(10));
            int second = allocateWith(pool, PageType.LEAF, key(2), key(20));

            pool.flush();

            assertThat(pool.isDirty(first)).isFalse();
            assertThat(pool.isDirty(second)).isFalse();
            assertThat(pageFromFile(pager, first).key(0)).containsExactly(key(1));
            assertThat(pageFromFile(pager, second).key(0)).containsExactly(key(2));
        }
    }

    @Test
    void whenEveryFrameIsPinnedThePoolRefusesRatherThanTakingOneBack() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            for (int i = 0; i < SMALL_POOL; i++) {
                pool.get(pool.allocate(PageType.LEAF));
            }

            assertThatThrownBy(() -> pool.allocate(PageType.LEAF))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("all " + SMALL_POOL + " frames are pinned");
        }
    }

    @Test
    void aFullPoolGivesUpThePageThatWasReleasedLongestAgo() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int oldest = pool.allocate(PageType.LEAF);
            fillPool(pool, SMALL_POOL - 1);

            int oneTooMany = pool.allocate(PageType.LEAF);

            assertThat(pool.isResident(oldest)).isFalse();
            assertThat(pool.isResident(oneTooMany)).isTrue();
        }
    }

    @Test
    void aChangedVictimReachesTheFileBeforeItsFrameIsReused() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = allocateWith(pool, PageType.LEAF, key(7), key(70));
            assertThat(pool.isDirty(pageId)).isTrue();

            fillPool(pool, SMALL_POOL);

            assertThat(pool.isResident(pageId)).isFalse();
            assertThat(pageFromFile(pager, pageId).key(0))
                    .as("evicting a changed page costs a write, and that write must happen")
                    .containsExactly(key(7));
        }
    }

    @Test
    void anUnchangedVictimIsDroppedWithoutBeingWrittenBack() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = allocateWith(pool, PageType.LEAF, key(1), key(10));
            pool.flush();

            // Change the file behind the pool's back. Its frame still holds the old page, clean.
            ByteBuffer replacement = ByteBuffer.allocate(Page.SIZE);
            SlottedPage.init(replacement, PageType.LEAF).insertCell(0, key(2), key(20));
            pager.writePage(pageId, replacement);

            fillPool(pool, SMALL_POOL);
            SlottedPage back = pool.get(pageId);
            try {
                assertThat(back.key(0))
                        .as("writing a clean frame back would have clobbered the file")
                        .containsExactly(key(2));
            } finally {
                pool.release(pageId);
            }
        }
    }

    @Test
    void anEvictedPageComesBackFromTheFileUnchanged() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = allocateWith(pool, PageType.LEAF, key(3), key(30));
            fillPool(pool, SMALL_POOL);
            assertThat(pool.isResident(pageId)).isFalse();

            SlottedPage back = pool.get(pageId);
            try {
                assertThat(back.key(0)).containsExactly(key(3));
                assertThat(back.value(0)).containsExactly(key(30));
            } finally {
                pool.release(pageId);
            }
        }
    }

    @Test
    void aBorrowedPageIsNeverChosenAsAVictim() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int borrowed = pool.allocate(PageType.LEAF);
            pool.get(borrowed);
            int idle = pool.allocate(PageType.LEAF);

            fillPool(pool, SMALL_POOL);

            assertThat(pool.isResident(borrowed)).as("someone is still holding it").isTrue();
            assertThat(pool.isResident(idle)).isFalse();
            pool.release(borrowed);
        }
    }

    @Test
    void usingAPageAgainMovesItToTheBackOfTheQueue() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int touchedAgain = pool.allocate(PageType.LEAF);
            int leftAlone = pool.allocate(PageType.LEAF);

            pool.get(touchedAgain);
            pool.release(touchedAgain);
            fillPool(pool, SMALL_POOL - 1);

            assertThat(pool.isResident(leftAlone)).as("its release is now the oldest").isFalse();
            assertThat(pool.isResident(touchedAgain)).isTrue();
        }
    }

    @Test
    void aFreedFrameIsReusedBeforeAnythingIsEvicted() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int freed = pool.allocate(PageType.LEAF);
            int[] borrowed = new int[SMALL_POOL - 1];
            for (int i = 0; i < borrowed.length; i++) {
                borrowed[i] = pool.allocate(PageType.LEAF);
                pool.get(borrowed[i]);
            }

            pool.free(freed);
            int reused = pool.allocate(PageType.LEAF);

            assertThat(pool.isResident(freed)).isFalse();
            assertThat(pool.isResident(reused)).as("it took the freed frame, not a borrowed one").isTrue();
            for (int pageId : borrowed) {
                pool.release(pageId);
            }
        }
    }

    @Test
    void aFreedPageIsNotWrittenBackOnFlush() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = allocateWith(pool, PageType.LEAF, key(9), key(90));

            pool.free(pageId);
            pool.flush();

            assertThat(pageFromFile(pager, pageId).cellCount())
                    .as("a freed page is rubbish, so its bytes must not reach the file")
                    .isZero();
        }
    }

    @Test
    void freeingABorrowedPageIsRejected() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = pool.allocate(PageType.LEAF);
            pool.get(pageId);

            assertThatThrownBy(() -> pool.free(pageId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still borrowed");

            pool.release(pageId);
        }
    }

    @Test
    void releasingMoreOftenThanBorrowingIsRejected() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);
            int pageId = pool.allocate(PageType.LEAF);
            pool.get(pageId);
            pool.release(pageId);

            assertThatThrownBy(() -> pool.release(pageId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("released more often");
        }
    }

    @Test
    void releasingAPageThatIsNotInThePoolIsRejected() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);

            assertThatThrownBy(() -> pool.release(2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not in the pool");
        }
    }

    @Test
    void gettingAPageThatWasNeverAllocatedIsRejectedAndCostsNoFrame() {
        try (Pager pager = pager()) {
            BufferPool pool = BufferPool.of(pager, SMALL_POOL);

            assertThatThrownBy(() -> pool.get(99)).isInstanceOf(IllegalArgumentException.class);

            // A leaked frame would leave room for only seven, so the last of these would fail.
            for (int i = 0; i < SMALL_POOL; i++) {
                pool.get(pool.allocate(PageType.LEAF));
            }
        }
    }

    @Test
    void aPoolTooSmallToHoldOneOperationIsRejected() {
        try (Pager pager = pager()) {
            assertThatThrownBy(() -> BufferPool.of(pager, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too small");
        }
    }
}
