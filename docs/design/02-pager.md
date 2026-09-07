# 2. Pager

## What it is

The file layer. The Pager owns one file on disk and treats it as an array of 4096-byte pages. It
turns a page id into a byte offset, reads that page into a buffer, and writes a buffer back.

It also owns page 0, the meta page. That page describes the file itself: what format it is, how big
its pages are, where the tree starts, and how many pages exist.

This is the only class in the engine that touches a `FileChannel`. Everything above it works in
page ids and never sees a byte offset.

## Why it is built this way

The tree above this layer already speaks in page ids. `PageStore` hands out ids, returns pages for
those ids, and takes them back. I built the whole tree against a `HashMap` implementation of that
interface before any file existed.

So the file layer only has to answer one question: where does page N live? I made that answer
arithmetic instead of a lookup:

```
offset of page N = N * Page.SIZE
```

No index, no map, no header per page telling me where the next one starts. A page id is a position.
That is why page ids are dense and always rise, and why `allocate()` can only append.

The meta page exists because the file has to be self-describing. Without it, opening a file written
by a different build, or opening a file that is not mine at all, would parse garbage as a page
header and produce nonsense instead of an error.

## Layout

### The file

```
  byte offset = pageId * 4096

  ┌──────────┬──────────┬──────────┬──────────┬──────────┬─────
  │  page 0  │  page 1  │  page 2  │  page 3  │  page 4  │ ...
  │   meta   │   root   │          │          │          │
  │          │  (leaf)  │          │          │          │
  └──────────┴──────────┴──────────┴──────────┴──────────┴─────
  0        4096       8192      12288      16384      20480

  file length == pageCount * 4096
```

Two things are fixed forever:

- Page 0 is the meta page.
- Page 1 is the root. The tree keeps its root page id when it grows and when it collapses, so this
  is written once at format time and never changes.
- Page 1 always holds a real page, not zeroes. A new file gets an empty leaf there.

### The meta page

All fields are 4-byte big-endian ints. Everything after offset 20 is zero and reserved.

| Offset | Size | Field | What it is for |
|--------|------|-------|----------------|
| 0 | 4 | magic | `"BPLS"`. Opening some other file fails instead of misparsing it |
| 4 | 4 | formatVersion | Refuse a file written by a newer build |
| 8 | 4 | pageSize | A build compiled for a different page size must detect it, never assume |
| 12 | 4 | rootPageId | Where the tree starts |
| 16 | 4 | pageCount | Pages in the file, so `allocate` knows where to append |

## Example

A file that has just been created, then had one extra page allocated. Page 0 reads:

```
0000   42 50 4C 53    magic          "BPLS"
0004   00 00 00 01    formatVersion  1
0008   00 00 10 00    pageSize       4096
000C   00 00 00 01    rootPageId     1
0010   00 00 00 03    pageCount      3
0014   00 00 ...      reserved, zero to the end of the page
```

`pageCount` is 3, so the file is 3 * 4096 = 12288 bytes: meta, root, and the one page I allocated.
That allocated page is page 2, and it lives at byte 8192.

At format time `pageCount` was 2. Every `allocate()` since then has bumped it by one.

## How it works

### open

```
file size == 0  ->  create it
file size >  0  ->  open it
```

**Create.** Build a meta page in memory with `rootPageId = 1` and `pageCount = 2`. Write it at
offset 0. Write an empty leaf at offset 4096 for the root. The file is now 8192 bytes and valid.

I format that root page instead of zeroing it because a zeroed page is not an empty page:

```text
zeroed page          empty leaf
-----------          ----------
type byte   = 0      type byte     = 0     both read as LEAF
cellCount   = 0      cellCount     = 0
cellAreaStart = 0    cellAreaStart = 4096
      |                    |
      v                    v
free space = -24     free space = 4072
```

A zeroed page passes every check `SlottedPage.wrap` makes, because type code 0 really is `LEAF`. It
only goes wrong later, when the first insert into an empty tree fails with "page full". Writing the
page properly at format time turns "page 1 is a valid empty leaf" into part of the file format,
next to "page 0 is the meta page".

**Open.** Read 4096 bytes from offset 0 and hand them to `MetaPage.open`, which checks magic,
version, and page size. Then check the file is at least `pageCount * Page.SIZE` bytes long. If it
is shorter, the meta page is describing pages that are not there, so I refuse rather than read past
the end later.

The meta page stays in memory for the life of the Pager. Reading `pageCount()` is a field read, not
a disk read.

### allocate

```
pageId = pageCount
pageCount = pageCount + 1
write a zeroed page at pageId
return pageId
```

Three lines, and every one of them matters. The new id is the old count because ids are dense.
Writing the zeroed page is what actually extends the file — without it the file would be short of
what the meta page claims, which is exactly the state `open` refuses.

A freshly allocated page is all zeroes. There is no free list, so an id is never reused.

### readPage / writePage

```
offset = pageId * Page.SIZE
loop until the buffer is full / drained
```

Both use positional channel calls, so the file's own position never moves and the two are
independent. Both loop, because a single `read` or `write` is allowed to move fewer than 4096
bytes.

Neither touches the caller's buffer position or limit. I work on a `duplicate().clear()`, so a
buffer the caller has positioned or narrowed still comes back exactly as it was handed in.

`readPage` refuses a page id that was never allocated. `writePage` does not, because `allocate`
itself writes the page that makes the id valid.

### sync and close

`sync()` writes the in-memory meta page to page 0 and calls `force(true)`.

`close()` syncs, then closes the channel. Calling it twice is fine. Every use after close throws.

This is where the honest limit lives: a clean `close()` persists everything, but a crash loses the
allocations made since the last `sync()`. The data pages are on disk; the meta page still says
there are fewer of them. Fixing that properly is durability, and I left durability out of this
milestone on purpose.

## Classes

| Class | What it owns |
|-------|--------------|
| `Pager` | The `FileChannel`, the page-id to byte-offset arithmetic, allocation, sync and close |
| `MetaPage` | The byte layout of page 0 and the checks that decide whether a file is mine |
| `PageBuffers` | The shared buffer view: writable, exactly `Page.SIZE`, big-endian, cleared |
| `Page` | `SIZE` and `NO_PAGE` |

## Limits and errors

| Situation | What happens |
|-----------|--------------|
| File is not a bplus-engine file | `IllegalArgumentException`, "not a bplus-engine file" |
| File format is newer than this build | `IllegalArgumentException` naming both versions |
| File uses a different page size | `IllegalArgumentException` naming both sizes |
| Meta page claims more pages than the file holds | `UncheckedIOException`, "truncated" |
| Page id was never allocated | `IllegalArgumentException`, "never allocated" |
| Buffer is not exactly `Page.SIZE` | `IllegalArgumentException` |
| Any use after `close()` | `IllegalStateException` |
| Any `IOException` | Wrapped in `UncheckedIOException` with the page id in the message |

## What is missing

| Missing | Why |
|---------|-----|
| Free page list | A deleted page is never reused, so the file only grows. Needs a free-list head in the meta page |
| Crash safety | No WAL, no copy-on-write, no checksums. Milestone 6 |
| Caching | Every read goes to the channel. That is milestone 3, the BufferPool |
| Concurrency | One thread at a time. No locking anywhere |
