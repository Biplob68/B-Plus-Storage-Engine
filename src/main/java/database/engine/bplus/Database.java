package database.engine.bplus;

import database.engine.bplus.store.BufferPool;
import database.engine.bplus.store.Pager;
import database.engine.bplus.tree.BPlusTree;
import database.engine.bplus.tree.Cursor;

import java.nio.file.Path;
import java.util.Objects;


public final class Database implements AutoCloseable {

    private final Pager pager;
    private final BufferPool pool;
    private final BPlusTree tree;
    private boolean closed;

    private Database(Pager pager, BufferPool pool, BPlusTree tree) {
        this.pager = pager;
        this.pool = pool;
        this.tree = tree;
    }

    public static Database open(Path path) {
        Objects.requireNonNull(path, "path");
        Pager pager = Pager.open(path);
        try {
            BufferPool pool = BufferPool.of(pager);
            return new Database(pager, pool, BPlusTree.open(pool, pager.rootPageId()));
        } catch (RuntimeException e) {
            pager.close();
            throw e;
        }
    }

    public byte[] get(byte[] key) {
        requireOpen();
        return tree.get(key);
    }

    public void put(byte[] key, byte[] value) {
        requireOpen();
        tree.put(key, value);
    }

    public boolean delete(byte[] key) {
        requireOpen();
        return tree.delete(key);
    }

    public Cursor scan() {
        requireOpen();
        return tree.scan();
    }

    public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
        requireOpen();
        return tree.scan(fromInclusive, toExclusive);
    }

    public void sync() {
        requireOpen();
        pool.flush();
        pager.sync();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        pool.flush();
        closed = true;
        pager.close();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("database is closed");
        }
    }
}
