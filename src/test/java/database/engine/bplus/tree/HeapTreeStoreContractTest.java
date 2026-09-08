package database.engine.bplus.tree;

class HeapTreeStoreContractTest extends TreeStoreContractTest {

    private HeapPageStore heap;

    @Override
    protected PageStore createStore() {
        heap = new HeapPageStore();
        return heap;
    }

    @Override
    protected int borrowedCount() {
        return heap.borrowedCount();
    }

    @Override
    protected void releaseStore() {
        // nothing to close, the pages are on the Java heap
    }
}
