package database.engine.bplus.page;

import java.nio.ByteBuffer;

/**
 * Page 0, which describes the file rather than holding records.
 *
 * <pre>
 *    0  4  magic          "BPLS". Opening something else fails instead of misparsing
 *    4  4  formatVersion  refuse a file written by a newer build
 *    8  4  pageSize       a build compiled for another size must detect it, never assume
 *   12  4  rootPageId     where the tree starts
 *   16  4  pageCount      pages in the file, so allocate knows where to append
 * </pre>
 */
public final class MetaPage {

    public static final int MAGIC = 0x42504C53;
    public static final int FORMAT_VERSION = 1;
    private static final int OFF_MAGIC = 0;
    private static final int OFF_FORMAT_VERSION = 4;
    private static final int OFF_PAGE_SIZE = 8;
    private static final int OFF_ROOT_PAGE_ID = 12;
    private static final int OFF_PAGE_COUNT = 16;

    private final ByteBuffer buffer;

    private MetaPage(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    public static MetaPage init(ByteBuffer buffer, int rootPageId, int pageCount) {
        ByteBuffer pageBuffer = PageBuffers.view(buffer);
        PageBuffers.zero(pageBuffer);
        pageBuffer.putInt(OFF_MAGIC, MAGIC);
        pageBuffer.putInt(OFF_FORMAT_VERSION, FORMAT_VERSION);
        pageBuffer.putInt(OFF_PAGE_SIZE, Page.SIZE);
        pageBuffer.putInt(OFF_ROOT_PAGE_ID, rootPageId);
        pageBuffer.putInt(OFF_PAGE_COUNT, pageCount);
        return new MetaPage(pageBuffer);
    }


    public static MetaPage open(ByteBuffer buffer) {
        MetaPage meta = new MetaPage(PageBuffers.view(buffer));
        meta.validatePage();
        return meta;
    }

    public int rootPageId() {
        return buffer.getInt(OFF_ROOT_PAGE_ID);
    }

    public int pageCount() {
        return buffer.getInt(OFF_PAGE_COUNT);
    }

    public void pageCount(int pageCount) {
        buffer.putInt(OFF_PAGE_COUNT, pageCount);
    }

    public int formatVersion() {
        return buffer.getInt(OFF_FORMAT_VERSION);
    }

    public int pageSize() {
        return buffer.getInt(OFF_PAGE_SIZE);
    }

    private void validatePage() {
        int magic = buffer.getInt(OFF_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "not a bplus-engine file: magic was 0x" + Integer.toHexString(magic));
        }
        int version = formatVersion();
        if (version > FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "file format " + version + " is newer than this build understands (" + FORMAT_VERSION + ")");
        }
        int pageSize = pageSize();
        if (pageSize != Page.SIZE) {
            throw new IllegalArgumentException(
                    "file uses " + pageSize + "-byte pages, this build uses " + Page.SIZE);
        }
    }
}
