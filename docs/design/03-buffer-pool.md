# 3. BufferPool

## What it is

`BufferPool` is an in-memory cache for B+ Tree pages.

```text
BPlusTree
    |
    v
BufferPool (RAM)
    |
    v
Pager
    |
    v
database.db (Disk)
```

The tree asks for a `pageId`.

```text
             get(pageId)
                 |
                 v
           +------------+
           | BufferPool |
           +------------+
             /        \
        in RAM?       not in RAM?
          |               |
          v               v
       return         Pager.readPage()
                          |
                          v
                     load into frame
                          |
                          v
                        return
```

---

## Page vs Frame

A **page** is a logical 4 KB block in the database file.

A **frame** is a 4 KB RAM slot that temporarily holds one page.

```text
DISK                           RAM

database.db                    BufferPool

Page 0  Meta
Page 1  Root  ------------->   Frame 0 -> Page 1
Page 2
Page 3  Leaf  ------------->   Frame 1 -> Page 3
Page 4
Page 5                       Frame 2 -> empty
```

A frame can later hold another page:

```text
Before:

Frame 2 -> Page 5

Later:

Frame 2 -> Page 10
```

So:

```text
Page  = data in the database file
Frame = RAM slot holding that page temporarily
```

---

## What a Frame holds

```text
Frame
+----------------------+
| pageId   = 7         |
| pinCount = 2         |
| dirty    = true      |
| buffer   = 4096 B    |
+----------------------+
```

| Field | Meaning |
|---|---|
| `buffer` | Actual page bytes |
| `pageId` | Which page is currently in the frame |
| `pinCount` | Number of callers currently using the page |
| `dirty` | Page may have changed and must be written back |

---

## Main BufferPool structures

```text
frames[]
+---------+---------+---------+---------+
| Frame 0 | Frame 1 | Frame 2 | Frame 3 |
+---------+---------+---------+---------+

residentFrames
pageId -> Frame

2 -> Frame 0
5 -> Frame 1
8 -> Frame 2

freeFrames
[ Frame 3, ... ]

evictable
oldest release -> [ Frame 1, Frame 2 ] <- newest release
```

- `frames[]` → all RAM frames
- `residentFrames` → quickly finds which frame contains a page
- `freeFrames` → unused frames available for new pages
- `evictable` → unpinned frames, oldest release first. The queue eviction picks from

During successful operations, a frame is free, evictable, or pinned. It is never both free and
evictable. The failure-path limits below describe cases where this bookkeeping is not restored.

```text
pinCount = 0  ->  in evictable   (can be taken)
pinCount > 0  ->  in neither     (cannot be taken)
holds nothing ->  in freeFrames
```

A pinned frame is not in the queue at all. That is what makes it impossible to evict.

---

## Pin Count

When the tree gets a page:

```text
get(7)

Page 7
pinCount: 0 -> 1
```

If the same page is borrowed again:

```text
get(7)

pinCount: 1 -> 2
```

When callers finish:

```text
release(7)

2 -> 1 -> 0
```

```text
pinCount > 0
    =
someone is still using the frame
    =
do not reuse it
```

This matters because `SlottedPage` directly wraps the frame's buffer.

---

## Dirty Page

After allocation or release:

```text
Frame 1 -> Page 7
dirty = true
```

It means:

```text
RAM version may differ from Disk version
```

Later:

```text
BufferPool.flush()
        |
        v
Pager.writePage(7)
        |
        v
database.db
```

After writing:

```text
dirty = false
```

---

## `get(pageId)`

### Cache hit

```text
BPlusTree
    |
 get(3)
    |
    v
BufferPool
    |
 Page 3 already resident
    |
    v
pin + return
```

No disk read.

### Cache miss

```text
BPlusTree
    |
 get(7)
    |
    v
BufferPool
    |
 Page 7 missing
    |
    v
take free Frame
    |
    v
Pager.readPage(7)
    |
    v
Frame <- Page 7 bytes
    |
    v
pin + return
```

---

## `allocate(type)`

```text
take free frame
      |
      v
Pager.allocate()
      |
      v
new pageId
      |
      v
initialize SlottedPage
      |
      v
mark dirty
```

---

## `release(pageId)`

```text
get(7)
  |
pin = 1
  |
release(7)
  |
pin = 0
```

The current implementation also marks the page dirty on release because the pool cannot know whether the caller only read or modified the page.

When the last caller releases a page, its frame joins the **back** of `evictable`.

```text
release(7)
  |
pin = 0
  |
  v
evictable: [ ... , Frame of 7 ]
```

---

## `flush()`

```text
BufferPool

Frame 0 -> Page 2  dirty
Frame 1 -> Page 5  clean
Frame 2 -> Page 8  dirty

            |
          flush()
            |
      +-----+-----+
      v           v
 write Page 2   write Page 8
      |           |
      +-----+-----+
            v
           Pager
            |
            v
       database.db
```

---

## Eviction

The pool has a few frames. The tree has a whole file of pages. Sooner or later every frame is
taken and the tree asks for one more.

Eviction is what happens then: put one page back, use its frame for the new one.

---

### The desk

```text
disk  = a big bookshelf, 1000 books
pool  = a small desk, only 3 books fit
```

A book must be on the desk before I can read it.

```text
take book 2     [2][ ][ ]
take book 5     [2][5][ ]
take book 8     [2][5][8]   <- desk full
```

Now I need book 9. There is no space, so one book goes back to the shelf.

```text
[2][5][8]   ->   [9][5][8]
 ^
 back to the shelf
```

That is eviction. Nothing more.

---

### Which page goes back

The one I finished with longest ago.

```text
evictable
front                              back
[ Page 2 , Page 5 , Page 8 ]
   ^
   goes first
```

Picking a page up takes it out of the queue. Putting it down adds it to the back.

```text
get(2)      evictable: [ Page 5 , Page 8 ]
release(2)  evictable: [ Page 5 , Page 8 , Page 2 ]
```

So a page I keep using keeps moving away from the front. It is the last one to go.

---

### One question before it goes back

Did I write in it?

```text
clean frame    ->  drop it without writing
dirty frame    ->  write it to the file first
```

That is the dirty flag. The current pool marks every release dirty, including read-only borrows.
A page is clean after a successful flush until it is released again.

```text
Frame 0 -> Page 2  clean  ->  just drop it
Frame 1 -> Page 5  dirty  ->  write Page 5, then drop it
```

A clean page needs no write-back. Eviction skips that unnecessary I/O.
Changing the file behind the pool or opening concurrent writers is not supported.

---

### A page in use is never taken

If my hand is still on the book, nobody can take it.

```text
pinCount > 0  ->  in use, cannot be taken
pinCount = 0  ->  finished with, can be taken
```

This is not a check. A pinned frame is never put in the `evictable` queue, and the queue is the
only place victims come from. There is no branch that could forget it.

That matters because `get` hands back a page pointing straight at the frame's buffer, not a copy.
Taking a frame someone still holds would quietly point their page at different bytes. Nothing would
throw. The wrong data would just turn up later.

---

### The whole thing

```text
need a frame
      |
      v
any free frame?  -- yes -->  take it. Free.
      |
      no
      |
      v
any unused page? -- no  -->  "buffer pool is full: all N frames are pinned"
      |
      yes
      |
      v
take the one finished with longest ago
      |
      v
   dirty?  -- yes -->  write it to the file
      |
      no
      |
      v
reuse the frame
```

---

### When it still refuses

```text
all 8 frames pinned
        +
allocate()
        |
        v
"buffer pool is full: all 8 frames are pinned"
```

Every frame borrowed and nothing given back means the tree wants more pages at once than the pool
holds. That is a real bug, or a pool built too small. It fails loudly instead of corrupting a page.

---

### The words

| Desk | Code |
|------|------|
| desk slot | frame |
| holding a book | pinned |
| may have written in it | dirty |
| put a book back | evict |
| copy writing to the shelf | write to the file |

---

### What is still missing

There is no free page list, so a freed page id is abandoned and the file only grows.

`flush()` can write pinned frames. `Database` calls it between operations in the supported
single-threaded flow. Direct pool callers must finish and release their changes before flushing.
Flush writes frames through the pager; it does not call `force`. Use `Database.sync()` to flush
frames and then sync the pager.

Two failure paths still need fixes:

- `get()` pins a frame before validating its page type. If validation throws, the pin is not undone.
- Eviction removes its candidate before write-back. If writing throws, the frame stays resident
  but is missing from the eviction queue.

`free(pageId)` releases a cached frame only. It does not mark the disk page invalid or prevent a
later get of that ID. Callers must remove all tree links to the page before freeing it.

---

## Simple mental model

```text
BPlusTree
   |
   | wants pages
   v
BufferPool
   |
   | manages RAM frames
   v
Pager
   |
   | reads/writes 4 KB pages
   v
database.db
```

```text
Page       = logical database page
Frame      = RAM slot for one page
BufferPool = manages frames
Pager      = moves pages between RAM and disk
```
