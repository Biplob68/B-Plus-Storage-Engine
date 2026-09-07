package database.engine.bplus.tree;

import database.engine.bplus.store.BufferPool;
import database.engine.bplus.store.Pager;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileTreeStoreContractTest extends TreeStoreContractTest {

    private static final int FRAME_COUNT = 8;

    @TempDir
    Path directory;

    private Pager pager;
    private BufferPool pool;

    @Override
    protected PageStore createStore() {
        pager = Pager.open(directory.resolve("contract.db"));
        pool = BufferPool.of(pager, FRAME_COUNT);
        return pool;
    }

    @Override
    protected int borrowedCount() {
        return pool.borrowedCount();
    }

    @Override
    protected void releaseStore() {
        assertThat(pager.pageCount())
                .as("a scenario that fits in the pool never evicts, and proves nothing")
                .isGreaterThan(FRAME_COUNT);
        pool.flush();
        pager.close();
    }
}
