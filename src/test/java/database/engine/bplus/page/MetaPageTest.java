package database.engine.bplus.page;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaPageTest {

    private static ByteBuffer freshPage() {
        return ByteBuffer.allocate(Page.SIZE);
    }

    private static ByteBuffer metaOf(int rootPageId, int pageCount) {
        ByteBuffer buffer = freshPage();
        MetaPage.init(buffer, rootPageId, pageCount);
        return buffer;
    }

    @Test
    void initThenOpenReadsTheSameFile() {
        ByteBuffer buffer = metaOf(1, 2);

        MetaPage meta = MetaPage.open(buffer);

        assertThat(meta.rootPageId()).isEqualTo(1);
        assertThat(meta.pageCount()).isEqualTo(2);
        assertThat(meta.formatVersion()).isEqualTo(MetaPage.FORMAT_VERSION);
        assertThat(meta.pageSize()).isEqualTo(Page.SIZE);
    }


    @Test
    void theLayoutIsExactlyAsDocumented() {
        ByteBuffer buffer = metaOf(1, 2);

        assertThat(buffer.getInt(0)).as("magic").isEqualTo(0x42504C53);
        assertThat(buffer.getInt(4)).as("formatVersion").isEqualTo(1);
        assertThat(buffer.getInt(8)).as("pageSize").isEqualTo(4096);
        assertThat(buffer.getInt(12)).as("rootPageId").isEqualTo(1);
        assertThat(buffer.getInt(16)).as("pageCount").isEqualTo(2);

        byte[] first20 = new byte[20];
        buffer.get(0, first20);
        assertThat(first20).containsExactly(
                0x42, 0x50, 0x4C, 0x53,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x10, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x02);
    }

    @Test
    void initZeroesEverythingElseSoNoOldBytesLeak() {
        ByteBuffer buffer = freshPage();
        for (int i = 0; i < Page.SIZE; i++) {
            buffer.put(i, (byte) 0xEE);
        }

        MetaPage.init(buffer, 1, 2);

        byte[] tail = new byte[Page.SIZE - 20];
        buffer.get(20, tail);
        assertThat(tail).containsOnly((byte) 0);
    }

    @Test
    void pageCountCanBeBumpedAsTheFileGrows() {
        ByteBuffer buffer = metaOf(1, 2);
        MetaPage meta = MetaPage.open(buffer);

        meta.pageCount(37);

        assertThat(meta.pageCount()).isEqualTo(37);
        assertThat(MetaPage.open(buffer).pageCount()).as("it went to the bytes").isEqualTo(37);
        assertThat(MetaPage.open(buffer).rootPageId()).as("nothing else moved").isEqualTo(1);
    }

    @Test
    void somethingThatIsNotOurFileIsRejected() {
        ByteBuffer notOurs = freshPage();
        notOurs.putInt(0, 0xFFD8FFE0); // a JPEG, say

        assertThatThrownBy(() -> MetaPage.open(notOurs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a bplus-engine file");
    }

    @Test
    void aFileFromANewerBuildIsRejected() {
        ByteBuffer buffer = metaOf(1, 2);
        buffer.putInt(4, MetaPage.FORMAT_VERSION + 1);

        assertThatThrownBy(() -> MetaPage.open(buffer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newer than this build");
    }

    @Test
    void anOlderFormatIsStillReadable() {
        ByteBuffer buffer = metaOf(1, 2);
        buffer.putInt(4, MetaPage.FORMAT_VERSION - 1);

        assertThat(MetaPage.open(buffer).formatVersion()).isEqualTo(MetaPage.FORMAT_VERSION - 1);
    }

    @Test
    void aDifferentPageSizeIsRejected() {
        ByteBuffer buffer = metaOf(1, 2);
        buffer.putInt(8, 8192);

        assertThatThrownBy(() -> MetaPage.open(buffer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8192-byte pages");
    }

    @Test
    void rejectsBuffersThatAreNotOnePage() {
        assertThatThrownBy(() -> MetaPage.init(ByteBuffer.allocate(Page.SIZE - 1), 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetaPage.init(freshPage().asReadOnlyBuffer(), 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCallersBufferPositionIsLeftAlone() {
        ByteBuffer buffer = freshPage();
        buffer.position(100).limit(200);

        MetaPage.init(buffer, 1, 2);

        assertThat(buffer.position()).isEqualTo(100);
        assertThat(buffer.limit()).isEqualTo(200);
    }
}
