package database.engine.bplus.store;

import database.engine.bplus.page.MetaPage;
import database.engine.bplus.page.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagerTest {

    @TempDir
    Path directory;

    private Path dbFile() {
        return directory.resolve("test.db");
    }

    private static ByteBuffer pageFilledWith(int marker) {
        ByteBuffer page = ByteBuffer.allocate(Page.SIZE);
        byte[] bytes = new byte[Page.SIZE];
        Arrays.fill(bytes, (byte) marker);
        page.put(0, bytes);
        return page;
    }

    private static ByteBuffer readPageOf(Pager pager, int pageId) {
        ByteBuffer page = ByteBuffer.allocate(Page.SIZE);
        pager.readPage(pageId, page);
        return page;
    }

    private static byte[] bytesOf(ByteBuffer page) {
        byte[] bytes = new byte[Page.SIZE];
        page.get(0, bytes);
        return bytes;
    }

    @Test
    void aNewFileIsFormattedWithAMetaPageAndARootPage() throws IOException {
        Path file = dbFile();

        try (Pager pager = Pager.open(file)) {
            assertThat(pager.rootPageId()).isEqualTo(Pager.ROOT_PAGE_ID);
            assertThat(pager.pageCount()).as("meta plus root").isEqualTo(2);
        }

        assertThat(Files.size(file)).isEqualTo(2L * Page.SIZE);
    }

    @Test
    void aPageWrittenComesBackByteForByte() {
        try (Pager pager = Pager.open(dbFile())) {
            ByteBuffer written = pageFilledWith(0xAB);
            pager.writePage(Pager.ROOT_PAGE_ID, written);

            assertThat(bytesOf(readPageOf(pager, Pager.ROOT_PAGE_ID))).containsExactly(bytesOf(written));
        }
    }

    @Test
    void everythingSurvivesAReopen() {
        Path file = dbFile();
        int extraPageId;

        try (Pager pager = Pager.open(file)) {
            pager.writePage(Pager.ROOT_PAGE_ID, pageFilledWith(0x11));
            extraPageId = pager.allocate();
            pager.writePage(extraPageId, pageFilledWith(0x22));
        }

        try (Pager reopened = Pager.open(file)) {
            assertThat(reopened.rootPageId()).isEqualTo(Pager.ROOT_PAGE_ID);
            assertThat(reopened.pageCount()).isEqualTo(3);
            assertThat(bytesOf(readPageOf(reopened, Pager.ROOT_PAGE_ID))).containsOnly((byte) 0x11);
            assertThat(bytesOf(readPageOf(reopened, extraPageId))).containsOnly((byte) 0x22);
        }
    }

    @Test
    void allocateHandsOutRisingIdsAndGrowsTheFile() throws IOException {
        Path file = dbFile();

        try (Pager pager = Pager.open(file)) {
            assertThat(pager.allocate()).isEqualTo(2);
            assertThat(pager.allocate()).isEqualTo(3);
            assertThat(pager.allocate()).isEqualTo(4);
            assertThat(pager.pageCount()).isEqualTo(5);
        }

        assertThat(Files.size(file)).as("the file covers every allocated page")
                .isEqualTo(5L * Page.SIZE);
    }

    @Test
    void aFreshlyAllocatedPageIsZeroed() {
        try (Pager pager = Pager.open(dbFile())) {
            pager.writePage(Pager.ROOT_PAGE_ID, pageFilledWith(0xFF));
            int pageId = pager.allocate();

            assertThat(bytesOf(readPageOf(pager, pageId))).containsOnly((byte) 0);
        }
    }

    @Test
    void readingAPageThatWasNeverAllocatedIsRejected() {
        try (Pager pager = Pager.open(dbFile())) {
            assertThatThrownBy(() -> readPageOf(pager, 99))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("never allocated");
            assertThatThrownBy(() -> readPageOf(pager, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aBufferThatIsNotOnePageIsRejected() {
        try (Pager pager = Pager.open(dbFile())) {
            ByteBuffer tooSmall = ByteBuffer.allocate(Page.SIZE - 1);

            assertThatThrownBy(() -> pager.readPage(Pager.ROOT_PAGE_ID, tooSmall))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pager.writePage(Pager.ROOT_PAGE_ID, tooSmall))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void openingSomethingThatIsNotOurFileFails() throws IOException {
        Path file = dbFile();
        byte[] junk = new byte[Page.SIZE * 2];
        Arrays.fill(junk, (byte) 0x7E);
        Files.write(file, junk);

        assertThatThrownBy(() -> Pager.open(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a bplus-engine file");
    }

    @Test
    void openingAFileWithADifferentPageSizeFails() throws IOException {
        Path file = dbFile();
        ByteBuffer meta = ByteBuffer.allocate(Page.SIZE);
        MetaPage.init(meta, Pager.ROOT_PAGE_ID, 2);
        meta.putInt(8, 8192);
        byte[] contents = new byte[Page.SIZE * 2];
        meta.get(0, contents, 0, Page.SIZE);
        Files.write(file, contents);

        assertThatThrownBy(() -> Pager.open(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8192-byte pages");
    }

    @Test
    void aTruncatedFileIsRefusedRatherThanRead() throws IOException {
        Path file = dbFile();
        try (Pager pager = Pager.open(file)) {
            pager.allocate();
            pager.allocate();
        }
        byte[] whole = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(whole, whole.length - Page.SIZE));

        assertThatThrownBy(() -> Pager.open(file))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void usingAClosedPagerIsRejectedAndClosingTwiceIsFine() {
        Pager pager = Pager.open(dbFile());
        pager.close();
        pager.close();

        assertThatThrownBy(() -> readPageOf(pager, Pager.ROOT_PAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(pager::allocate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCallersBufferPositionIsLeftAlone() {
        try (Pager pager = Pager.open(dbFile())) {
            ByteBuffer page = pageFilledWith(0x33);
            page.position(100).limit(200);

            pager.writePage(Pager.ROOT_PAGE_ID, page);

            assertThat(page.position()).isEqualTo(100);
            assertThat(page.limit()).isEqualTo(200);
        }
    }
}
