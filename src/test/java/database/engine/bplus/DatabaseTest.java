package database.engine.bplus;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.store.BufferPool;
import database.engine.bplus.store.Pager;
import database.engine.bplus.tree.Cursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTest {

    @TempDir
    Path directory;

    private Path dbFile() {
        return directory.resolve("test.db");
    }

    private static byte[] key(int n) {
        return new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
    }

    private static byte[] value(int n) {
        byte[] value = new byte[200];
        value[0] = (byte) n;
        value[199] = (byte) (n >>> 8);
        return value;
    }

    private static List<Integer> keysOf(Cursor cursor) {
        List<Integer> keys = new ArrayList<>();
        while (cursor.next()) {
            byte[] key = cursor.key();
            keys.add(((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16)
                    | ((key[2] & 0xFF) << 8) | (key[3] & 0xFF));
        }
        return keys;
    }

    private static int pageCountOf(Path file) {
        try (Pager pager = Pager.open(file)) {
            return pager.pageCount();
        }
    }

    private static int internalPageCountOf(Path file) {
        try (Pager pager = Pager.open(file)) {
            int internals = 0;
            for (int pageId = 1; pageId < pager.pageCount(); pageId++) {
                ByteBuffer buffer = ByteBuffer.allocate(Page.SIZE);
                pager.readPage(pageId, buffer);
                if (SlottedPage.wrap(buffer).type() == PageType.INTERNAL) {
                    internals++;
                }
            }
            return internals;
        }
    }

    private static void putRange(Database database, int keyCount) {
        for (int i = 0; i < keyCount; i++) {
            database.put(key(i), value(i));
        }
    }

    private static void assertHoldsRange(Database database, int keyCount) {
        for (int i = 0; i < keyCount; i++) {
            assertThat(database.get(key(i))).as("key " + i).containsExactly(value(i));
        }
        assertThat(keysOf(database.scan()))
                .as("still in key order")
                .containsExactlyElementsOf(IntStream.range(0, keyCount).boxed().toList());
    }

    @Test
    void aNewFileOpensAsAnEmptyDatabase() {
        try (Database database = Database.open(dbFile())) {
            assertThat(database.get(key(1))).isNull();
            assertThat(keysOf(database.scan())).isEmpty();
        }
    }

    @Test
    void whatIPutInComesBackAfterAClose() {
        Path file = dbFile();

        try (Database database = Database.open(file)) {
            database.put(key(1), value(1));
            database.put(key(2), value(2));
            database.put(key(3), value(3));
        }

        try (Database reopened = Database.open(file)) {
            assertThat(reopened.get(key(1))).containsExactly(value(1));
            assertThat(reopened.get(key(2))).containsExactly(value(2));
            assertThat(reopened.get(key(3))).containsExactly(value(3));
            assertThat(reopened.get(key(4))).isNull();
        }
    }

    @Test
    void aFileBiggerThanThePoolSurvivesAClose() {
        Path file = dbFile();
        int keyCount = 2000;

        try (Database database = Database.open(file)) {
            putRange(database, keyCount);
        }

        assertThat(pageCountOf(file))
                .as("the pool must have been evicting, or this test proves nothing")
                .isGreaterThan(BufferPool.DEFAULT_FRAME_COUNT);

        try (Database reopened = Database.open(file)) {
            assertHoldsRange(reopened, keyCount);
        }
    }

    @Test
    void aTreeDeepEnoughForTheInternalLevelToSplitSurvivesAClose() {
        Path file = dbFile();
        int keyCount = 4000;

        try (Database database = Database.open(file)) {
            putRange(database, keyCount);
        }

        assertThat(internalPageCountOf(file))
                .as("one internal page would only be a two level tree")
                .isGreaterThan(1);

        try (Database reopened = Database.open(file)) {
            assertHoldsRange(reopened, keyCount);
        }
    }

    @Test
    void replacingAValueSurvivesAClose() {
        Path file = dbFile();

        try (Database database = Database.open(file)) {
            database.put(key(7), value(7));
            database.put(key(7), value(70));
        }

        try (Database reopened = Database.open(file)) {
            assertThat(reopened.get(key(7))).containsExactly(value(70));
        }
    }

    @Test
    void aDeleteSurvivesAClose() {
        Path file = dbFile();

        try (Database database = Database.open(file)) {
            for (int i = 0; i < 200; i++) {
                database.put(key(i), value(i));
            }
            assertThat(database.delete(key(100))).isTrue();
            assertThat(database.delete(key(100))).isFalse();
        }

        try (Database reopened = Database.open(file)) {
            assertThat(reopened.get(key(100))).isNull();
            assertThat(reopened.get(key(99))).containsExactly(value(99));
            assertThat(keysOf(reopened.scan())).hasSize(199).doesNotContain(100);
        }
    }

    @Test
    void aRangeScanStillWorksAfterAReopen() {
        Path file = dbFile();

        try (Database database = Database.open(file)) {
            for (int i = 0; i < 300; i++) {
                database.put(key(i), value(i));
            }
        }

        try (Database reopened = Database.open(file)) {
            assertThat(keysOf(reopened.scan(key(10), key(20))))
                    .containsExactly(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        }
    }

    @Test
    void syncLeavesTheDatabaseUsable() {
        Path file = dbFile();

        try (Database database = Database.open(file)) {
            database.put(key(1), value(1));
            database.sync();

            database.put(key(2), value(2));
            assertThat(database.get(key(1))).containsExactly(value(1));
            assertThat(database.get(key(2))).containsExactly(value(2));
        }

        try (Database reopened = Database.open(file)) {
            assertThat(reopened.get(key(2))).containsExactly(value(2));
        }
    }

    @Test
    void reopeningManyTimesKeepsEverything() {
        Path file = dbFile();

        for (int round = 0; round < 5; round++) {
            try (Database database = Database.open(file)) {
                for (int i = 0; i < 20; i++) {
                    database.put(key(round * 20 + i), value(round * 20 + i));
                }
            }
        }

        try (Database reopened = Database.open(file)) {
            assertThat(keysOf(reopened.scan())).hasSize(100);
            assertThat(reopened.get(key(0))).containsExactly(value(0));
            assertThat(reopened.get(key(99))).containsExactly(value(99));
        }
    }

    @Test
    void usingAClosedDatabaseIsRejectedAndClosingTwiceIsFine() {
        Database database = Database.open(dbFile());
        database.put(key(1), value(1));
        database.close();
        database.close();

        assertThatThrownBy(() -> database.get(key(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> database.put(key(2), value(2)))
                .isInstanceOf(IllegalStateException.class);
    }
}
