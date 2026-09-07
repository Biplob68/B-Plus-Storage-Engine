package database.engine.bplus.store;

import database.engine.bplus.page.MetaPage;
import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The file. Turns a page id into a byte offset and moves 4 KB at a time.
 *
 * <pre>
 *   byte offset of page N = N * Page.SIZE
 *
 *   ┌──────────┬──────────┬──────────┬──────────┬─────
 *   │  page 0  │  page 1  │  page 2  │  page 3  │ ...
 *   │   meta   │   root   │          │          │
 *   │          │  (leaf)  │          │          │
 *   └──────────┴──────────┴──────────┴──────────┴─────
 *   0         4096       8192      12288      16384
 * </pre>
 */
public final class Pager implements AutoCloseable {

    public static final int META_PAGE_ID = 0;

    public static final int ROOT_PAGE_ID = 1;

    private static final int FIRST_FREE_PAGE_ID = ROOT_PAGE_ID + 1;

    private final FileChannel channel;
    private final ByteBuffer metaBuffer;
    private final MetaPage meta;
    private boolean closed;

    private Pager(FileChannel channel, ByteBuffer metaBuffer, MetaPage meta) {
        this.channel = channel;
        this.metaBuffer = metaBuffer;
        this.meta = meta;
    }

    public static Pager open(Path path) {
        try {
            FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            return channel.size() == 0 ? createNew(channel) : openExisting(channel);
        } catch (IOException e) {
            throw new UncheckedIOException("could not open " + path, e);
        }
    }

    private static Pager createNew(FileChannel channel) {
        ByteBuffer metaBuffer = ByteBuffer.allocate(Page.SIZE);
        MetaPage meta = MetaPage.init(metaBuffer, ROOT_PAGE_ID, FIRST_FREE_PAGE_ID);

        Pager pager = new Pager(channel, metaBuffer, meta);
        pager.writePage(META_PAGE_ID, metaBuffer);
        pager.writePage(ROOT_PAGE_ID, emptyLeaf());
        return pager;
    }


    private static ByteBuffer emptyLeaf() {
        ByteBuffer buffer = ByteBuffer.allocate(Page.SIZE);
        SlottedPage.init(buffer, PageType.LEAF);
        return buffer;
    }

    private static Pager openExisting(FileChannel channel) throws IOException {
        ByteBuffer metaBuffer = ByteBuffer.allocate(Page.SIZE);
        readFully(channel, metaBuffer.duplicate(), 0, META_PAGE_ID);
        MetaPage meta = MetaPage.open(metaBuffer);

        long expectedBytes = (long) meta.pageCount() * Page.SIZE;
        if (channel.size() < expectedBytes) {
            throw new UncheckedIOException(new IOException("file is truncated: holds " + channel.size()
                    + " bytes but its meta page claims " + meta.pageCount() + " pages"));
        }
        return new Pager(channel, metaBuffer, meta);
    }

    public int rootPageId() {
        return meta.rootPageId();
    }

    public int pageCount() {
        return meta.pageCount();
    }


    public int allocate() {
        requireOpen();
        int pageId = meta.pageCount();
        meta.pageCount(pageId + 1);
        writePage(pageId, ByteBuffer.allocate(Page.SIZE));
        return pageId;
    }

    public void readPage(int pageId, ByteBuffer destination) {
        requireOpen();
        requireAllocated(pageId);
        requireOnePage(destination);
        try {
            readFully(channel, destination.duplicate().clear(), offsetOf(pageId), pageId);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read page " + pageId, e);
        }
    }

    public void writePage(int pageId, ByteBuffer source) {
        requireOpen();
        requireOnePage(source);
        try {
            writeFully(channel, source.duplicate().clear(), offsetOf(pageId));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write page " + pageId, e);
        }
    }

    /**
     * Writes the meta page and forces everything down to the disk.
     */
    public void sync() {
        requireOpen();
        writePage(META_PAGE_ID, metaBuffer);
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("could not sync", e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        sync();
        closed = true;
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException("could not close", e);
        }
    }

    private static long offsetOf(int pageId) {
        return (long) pageId * Page.SIZE;
    }

    private static void readFully(FileChannel channel, ByteBuffer destination, long position, int pageId)
            throws IOException {
        long offset = position;
        while (destination.hasRemaining()) {
            int read = channel.read(destination, offset);
            if (read < 0) {
                throw new IOException("page " + pageId + " runs past the end of the file");
            }
            offset += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source, long position) throws IOException {
        long offset = position;
        while (source.hasRemaining()) {
            offset += channel.write(source, offset);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("pager is closed");
        }
    }

    private void requireAllocated(int pageId) {
        if (pageId < 0 || pageId >= meta.pageCount()) {
            throw new IllegalArgumentException(
                    "page " + pageId + " was never allocated; the file holds " + meta.pageCount() + " pages");
        }
    }

    private static void requireOnePage(ByteBuffer buffer) {
        if (buffer.capacity() != Page.SIZE) {
            throw new IllegalArgumentException(
                    "buffer capacity must be Page.SIZE, got " + buffer.capacity());
        }
    }
}
