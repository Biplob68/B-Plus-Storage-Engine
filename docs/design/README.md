# Design docs

This project is building a storage engine around a B+ tree: a sorted map of byte-array keys and values.

Start with the page layout, then read about the tree. The byte helpers explain how numbers and keys are stored.

## Reading order

| Doc | What you will learn |
|---|---|
| [Bytes](00-bytes.md) | Key ordering and number encoding |
| [Slotted pages](01-slotted-page.md) | How one 4 KB page stores records |
| [Pager](02-pager.md) | How pages are read from and written to a file |
| [Buffer pool](03-buffer-pool.md) | How pages are cached, borrowed, and evicted |
| [Database](03-database.md) | How opening, operations, sync, and close connect the engine |
| [Tree lookup and insertion](04-bplus-insert.md) | How the tree finds keys and grows |
| [Tree deletion](04-bplus-delete.md) | How the tree removes keys and shrinks |
| [Range scans](04-bplus-scan.md) | How to read keys in order |

## How the pieces fit

```text
Database: open, get, put, delete, scan, sync, close
    |
BPlusTree: key routing and page changes
    |
PageStore: allocate, get, release, free
    |
    +-- BufferPool -> Pager -> disk file
    |
    +-- HeapPageStore: in-memory test implementation
```

The tree works through the `PageStore` interface. `BufferPool` implements it using cached pages
and the disk pager. `Database` opens these pieces and flushes them in order on sync or close.

Shared contract tests run against both heap and file stores. Database tests cover close/reopen,
eviction, updates, deletes, scans, and internal-page splits. Crash recovery is still unimplemented.

## Milestones

These statuses describe the implementation, not the result of a new test run.

| # | Feature | Status |
|---|---|---|
| 0 | Byte helpers | Implemented |
| 1 | Slotted pages | Implemented |
| 2 | Disk pager | Implemented |
| 3 | Buffer pool and Database entry point | Implemented |
| 4 | B+ tree lookup, insertion, deletion, and scans | Implemented with heap and file stores |
| 5 | Broader oracle harness: compare operations with a reference map | Not started; some randomized tests already exist |
| 6 | Copy-on-write crash durability | Not started |
| 7 | Latch crabbing: coordinate access while moving through the tree | Not started |
| 8 | Benchmarks | Not started |

## Writing these docs

Explain the current behavior in short sentences. Define unfamiliar terms before using them.
Keep byte layouts and worked examples where they help. Label decimal offsets and hexadecimal bytes
clearly. Separate implemented behavior from planned work.
