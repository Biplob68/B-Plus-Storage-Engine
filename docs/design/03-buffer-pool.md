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
| `dirty` | Page changed in RAM and must be written to disk |

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
```

- `frames[]` → all RAM frames
- `residentFrames` → quickly finds which frame contains a page
- `freeFrames` → unused frames available for new pages

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

When a page changes in RAM:

```text
Frame 1 -> Page 7
dirty = true
```

It means:

```text
RAM version != Disk version
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

## Current limitation

There is no eviction yet.

```text
Frame 0 -> Page 2
Frame 1 -> Page 3
Frame 2 -> Page 4

All frames occupied
        +
tree asks for Page 10
        |
        v
"buffer pool is full"
```

Later, eviction can choose an unused frame (`pinCount = 0`) and reuse it.

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
