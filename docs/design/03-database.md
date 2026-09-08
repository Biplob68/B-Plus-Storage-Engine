# 3b. Database

## What it is

`Database` is the whole engine behind one class.

Open a file, put keys in, get them back, close it. Everything below is wiring.

```text
Database.open(Path.of("my.db"))
    |
    +-- BPlusTree    keys and values
    |       |
    |   BufferPool   which pages are in RAM
    |       |
    +-- Pager        the file
            |
            v
          my.db
```

Before this class existed the tree and the file had never met. The tree worked on a `HashMap`. The
file could store pages. Nothing joined them.

---

## Why it needed its own class

Three things have to be built in the right order, and two of them have to be shut down in the right
order. That is a job on its own.

```text
open                       close
----                       -----
Pager.open(path)           pool.flush()      pages first
BufferPool.of(pager)       pager.close()     meta page last
BPlusTree.open(pool, pager.rootPageId())
```

---

## The root is always page 1

```text
page 0   meta
page 1   root      <- always, forever
page 2   ...
```

The `Pager` reserves page 1 when it formats a new file and writes an empty leaf there. The tree
opens on that id.

It never moves. When the tree grows a level, `RootSplit` copies the old root out to a new page and
rewrites page 1 as the new internal node. When it shrinks, `RootCollapse` pulls the last child back
into page 1.

```text
grow                       shrink
----                       ------
page 1 = internal          page 1 = leaf again
   |     \                    |
 copy    new                 old
```

That is why `MetaPage` has no setter for `rootPageId`. It is written once and is true forever.

---

## `open(path)`

```text
file missing or empty?
      |
     yes ---> create it: meta page + empty leaf at page 1
      |
      no ---> check magic, version, page size, length
      |
      v
BufferPool over the pager
      |
      v
BPlusTree on page 1
```

If setup after `Pager.open` throws a runtime exception, `Database` attempts to close the pager.
Cleanup can also fail and replace the original error. Failed opening inside `Pager.open` can
still leave a channel open. A half-open database is not returned.

---

## `get` / `put` / `delete` / `scan`

These pass straight through to the tree. `Database` adds nothing except a check that it is still
open.

```text
database.put(key, value)
        |
        v
   tree.put(...)
        |
        v
   pool.get(pageId) ... pool.release(pageId)
        |
        v
   frames in RAM        <- changes happen here
        |
        +-- allocation -> zero-filled page written by Pager
        +-- eviction   -> dirty victim written before frame reuse
```

A `put` changes pages in RAM, but it can also write to disk when it allocates a page or evicts a
dirty frame. Cache misses can read from disk during any tree operation.

The pool marks every released page dirty because it hands out writable pages. Even a lookup or
scan can therefore cause later write-back. Dirty means the page may have changed, not that the
pool detected a change.

---

## `close()`

```text
pool.flush()     write every changed page
      |
      v
pager.close()    write the meta page, force, close the file
```

The order is the point.

The pool writes its dirty pages before the pager writes metadata and forces the file to disk.
Allocation has already extended the file with zero-filled pages; flushing writes their formatted
contents. This order supports a clean close, but it does not make several page writes atomic or
guarantee their persistence order during a crash.

`sync()` does the same two steps but leaves the database open.

---

## What survives what

| | Survives |
|---|---|
| Successful `close()` followed by reopen | Completed changes are written and the file is closed |
| Successful `sync()` | Current dirty pages and metadata are written and forced; the database stays open |
| Crash during later writes | No guaranteed recovery to the last sync |

There is no write-ahead log (WAL), copy-on-write, or recovery procedure. Later writes can overwrite
pages that were saved by an earlier sync. A crash can leave a partial page or an inconsistent tree,
so sync is not a snapshot that can be restored. Crash durability is milestone 6.

---

## Example: sequential insertion

4-byte keys, 200-byte values.

```text
cell  = varint(4) + varint(200) + 4 + 200 = 207 bytes
entry = cell + slot                       = 209 bytes

a leaf holds     4072 / 209 = 19 entries when packed full
after splitting  about 10 entries, because a split leaves both halves half full
```

An internal page stores a separator and a child id instead:

```text
cell  = varint(4) + varint(4) + 4 + 4 = 10 bytes
entry = cell + slot                   = 12 bytes

leftmost child entry = 8 bytes (empty key plus child ID and slot)
remaining entries    = floor((4072 - 8) / 12) = 338
children per page    = 1 + 338 = 339
```

For sequential ascending inserts with these key and value sizes, the layout is:

| Keys | Pages in file | Leaves | Internal pages | Levels |
|------|---------------|--------|----------------|--------|
| 2000 | 202 | 200 | 1 | 2 |
| 4000 | 404 | 400 | 3 | 3 |

At 2000 keys the 200 leaves still fit under one root, so the root is the only internal page.

At 4000 keys the 400 leaves no longer fit under 339, the root splits, and the tree grows a level:

```text
2000 keys                4000 keys

   root                     root
  /  |  \                  /    \
 200 leaves          internal  internal
                        |          |
                      400 leaves
```

Both examples exceed the default pool of 64 frames, so insertion must evict pages once the cache
fills. These counts depend on insertion order and record sizes; they are not general capacities.

---

## Limits and errors

| Situation | What happens |
|---|---|
| File is not a bplus-engine file | `IllegalArgumentException`, "not a bplus-engine file" |
| File written by a newer format | `IllegalArgumentException` naming both versions |
| Different page size | `IllegalArgumentException` naming both sizes |
| File shorter than the meta page claims | `UncheckedIOException`, "truncated" |
| Calls to get, put, delete, scan, or sync after close | `IllegalStateException`, "database is closed" |
| `close()` twice after success | The second call does nothing |
| Pool flush fails during close | Close stops before closing the pager |
| Pager close fails | Database is already marked closed; a later close does not retry |

---

## What is missing

| Missing | Why |
|---|---|
| Crash safety | No WAL, no copy-on-write, no checksums. Milestone 6 |
| Free page list | A merged-away page is abandoned, so the file only grows |
| Choosing the pool size | `open` always uses 64 frames |
| Concurrency | One thread. No locking anywhere |
| Failure cleanup | Opening and closing can leave channels open on errors |

A cursor returned before close has no database-lifecycle check. Do not use it after closing the
database; copied or cached entries may still be readable until it needs a disk read.

---

## Simple mental model

```text
Database   = the file, opened
BPlusTree  = where a key lives
BufferPool = which pages are in RAM right now
Pager      = moves 4 KB blocks to and from disk
```

```text
put  ->  change pages in RAM; allocation or eviction may write to disk
close ->  write the dirty pages, then the meta page
open  ->  read the meta page, find the root at page 1
```
