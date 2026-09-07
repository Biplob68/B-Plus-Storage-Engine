# Design docs

This project is building a storage engine around a B+ tree: a sorted map of byte-array keys and values.

Start with the page layout, then read about the tree. The byte helpers explain how numbers and keys are stored.

## Reading order

| Doc | What you will learn |
|---|---|
| [Bytes](00-bytes.md) | Key ordering and number encoding |
| [Slotted pages](01-slotted-page.md) | How one 4 KB page stores records |
| [Pager](02-pager.md) | How pages are read from and written to a file |
| [Tree lookup and insertion](04-bplus-insert.md) | How the tree finds keys and grows |
| [Tree deletion](04-bplus-delete.md) | How the tree removes keys and shrinks |
| [Range scans](04-bplus-scan.md) | How to read keys in order |

## How the pieces fit

```text
BPlusTree: get, put, delete, scan
    |
PageStore: allocate, get, release, free
    |
    +-- HeapPageStore: available in tests
    |
    +-- BufferPool: planned connection to Pager
                                |
                             disk file
```

The tree works through the `PageStore` interface. The disk pager exists, but there is no production
implementation connecting it to the tree yet. The tree tests use pages held in memory.

## Milestones

These statuses describe the implementation, not the result of a new test run.

| # | Feature | Status |
|---|---|---|
| 0 | Byte helpers | Implemented |
| 1 | Slotted pages | Implemented |
| 2 | Disk pager | Implemented |
| 3 | Buffer pool | Not started |
| 4 | B+ tree lookup, insertion, deletion, and scans | Implemented against PageStore |
| 5 | Broader oracle harness: compare operations with a reference map | Not started; some randomized tests already exist |
| 6 | Copy-on-write crash durability | Not started |
| 7 | Latch crabbing: coordinate access while moving through the tree | Not started |
| 8 | Benchmarks | Not started |

## Writing these docs

Explain the current behavior in short sentences. Define unfamiliar terms before using them.
Keep byte layouts and worked examples where they help. Label decimal offsets and hexadecimal bytes
clearly. Separate implemented behavior from planned work.
