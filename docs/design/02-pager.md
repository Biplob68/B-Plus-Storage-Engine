# 2. Disk pager

`Pager` reads and writes one file as fixed 4096-byte pages.
It owns the file channel and translates page IDs into file offsets.

```text
byte offset = page ID * 4096

page:    0          1          2          3
      +----------+----------+----------+----------+
      | metadata | root     | data     | data     |
      +----------+----------+----------+----------+
offset: 0          4096       8192       12288
```

The pager reserves page 0 for metadata and page 1 for the root.
New pages are appended. Freed pages are not reused yet.

The pager is separate from the tree. A future buffer pool will connect it to `PageStore`.

## Metadata layout

All offsets are decimal. Each field is a four-byte big-endian integer.

| Offset | Field | Initial value |
|---|---|---|
| 0 | magic | BPLS |
| 4 | formatVersion | 1 |
| 8 | pageSize | 4096 |
| 12 | rootPageId | 1 |
| 16 | pageCount | 2 |

Bytes from offset 20 onward are reserved and initially zero.

After allocating one extra page, the metadata contains:

```text
offset  hex bytes    meaning
0       42 50 4C 53  BPLS
4       00 00 00 01  format version 1
8       00 00 10 00  page size 4096
12      00 00 00 01  root page 1
16      00 00 00 03  three pages
```

The expected file size is then `3 * 4096 = 12288` bytes.

## Opening a file

For an empty file, the pager writes metadata and a zero-filled root page.
The root still needs to be formatted by the layer above the pager.

For an existing file, it reads metadata and checks the magic, supported version ceiling,
and page size. It also rejects a file shorter than the page count claims.
Extra trailing bytes are accepted.

These checks are limited: root ID, page count, and all possible invalid version values
are not fully validated.

## Allocating a page

1. Use the current page count as the new page ID.
2. Increase the in-memory page count.
3. Write a zero-filled page at the new offset.
4. Return its ID.

The metadata change reaches disk during `sync()` or a successful `close()`.
Currently, a failed page write does not roll back the in-memory count.

## Reading and writing

Both operations transfer a whole page using an absolute file offset.
They loop because one channel call may transfer fewer than 4096 bytes.

The supplied buffer must have capacity 4096. A duplicate buffer is used, so the caller's
position and limit stay unchanged.

`readPage` rejects unallocated page IDs. `writePage` does not check allocation bounds;
callers must supply the intended page ID.

## Sync and close

`sync()` writes the in-memory metadata and calls `FileChannel.force(true)`.
`close()` syncs first, then closes the channel. Calling close again after success does nothing.

This is not crash-safe transaction support. A crash before sync can leave appended data pages
that the stored metadata does not include. Copy-on-write durability is planned.

## Classes and limits

| Class | Responsibility |
|---|---|
| Pager | File access, allocation, sync, and close |
| MetaPage | Metadata fields and validation |
| PageBuffers | Shared page-buffer views |
| Page | Page size and constants |

I/O failures are reported as `UncheckedIOException`.
Read, write, allocate, and sync reject use after close; metadata getters still return their values.

There is no page cache, disk free list, or connection to the tree yet.
Error cleanup also needs work: failed opening can leave a channel open, and failed sync during
close prevents the channel from being closed.
