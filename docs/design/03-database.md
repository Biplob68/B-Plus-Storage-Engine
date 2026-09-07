# 3b. Database

## What it is

`Database` is the whole engine behind one class.

Open a file, put keys in, get them back, close it. Everything below is wiring.

```text
Database.open("my.db")
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
BPlusTree.open(pool, 1)
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

If anything after `Pager.open` fails, the pager is closed before the error leaves. A half-open
database is not handed back.

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
   frames in RAM        <- the file is not touched yet
```

Nothing reaches the file during a `put`. Pages are changed in frames and marked dirty.

---

## `close()`

```text
pool.flush()     write every changed page
      |
      v
pager.close()    write the meta page, force, close the file
```

The order is the point.

The meta page carries `pageCount`. Writing it **last** means it can never claim pages the file does
not hold yet. Writing it first would leave a window where the file says "I have 202 pages" and only
holds 150.

`sync()` does the same two steps but leaves the database open.

---

## What survives what

| | Survives |
|---|---|
| `close()` | everything |
| `sync()` | everything up to that point |
| crash | everything up to the last `sync()` or `close()` |

There is no WAL and no copy-on-write, so a crash mid-write can leave a page half written. I left
durability out on purpose. That is milestone 6.

---

## Example, measured

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

one internal page points at 4072 / 12 = 339 children
```

So the tree gets a third level somewhere between 3390 and 4000 keys. Both of these are real runs:

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

Both numbers also mean the pool was evicting the whole time. It holds 64 frames and the file has
202 or 404 pages.

---

## Limits and errors

| Situation | What happens |
|---|---|
| File is not a bplus-engine file | `IllegalArgumentException`, "not a bplus-engine file" |
| File written by a newer format | `IllegalArgumentException` naming both versions |
| Different page size | `IllegalArgumentException` naming both sizes |
| File shorter than the meta page claims | `UncheckedIOException`, "truncated" |
| Any use after `close()` | `IllegalStateException`, "database is closed" |
| `close()` twice | Fine, the second one does nothing |

---

## What is missing

| Missing | Why |
|---|---|
| Crash safety | No WAL, no copy-on-write, no checksums. Milestone 6 |
| Free page list | A merged-away page is abandoned, so the file only grows |
| Choosing the pool size | `open` always uses 64 frames |
| Concurrency | One thread. No locking anywhere |

---

## Simple mental model

```text
Database   = the file, opened
BPlusTree  = where a key lives
BufferPool = which pages are in RAM right now
Pager      = moves 4 KB blocks to and from disk
```

```text
put  ->  change a page in RAM, mark it dirty
close ->  write the dirty pages, then the meta page
open  ->  read the meta page, find the root at page 1
```
