# 1. SlottedPage

**Status:** Done · **Package:** `database.engine.bplus.page` · **Tests:** 18 passing

## What it is

A 4 KB block of bytes that stores many key/value pairs in sorted order, and lets me binary search
them.

A disk reads and writes fixed blocks, not Java objects. So a node in my tree has to be a block of
bytes. SlottedPage is that block.

Keys and values are `byte[]` of any length. They are ordered with `Arrays.compareUnsigned`.

This class does no file I/O. It also keeps no state of its own. Every field lives in the bytes of
the buffer it wraps.

## Why it is built this way

I cannot put records at fixed positions. A fixed position needs a fixed record size. My keys have
different lengths, so I would have to pad every key to the maximum. A page that fits 254 small
records would then fit 4.

There is a second problem. The page must stay sorted. A new key usually belongs somewhere in the
middle. With fixed positions I would have to move every record after it.

So the page is split into two parts. Small sorted pointers at the front, called **slots**. The
records at the back, called **cells**. Free space in the middle.

Now an insert moves 2-byte slots instead of records. A cell never moves when something else is
inserted.

Only the slot array is sorted. The cell area is just bytes sitting in arrival order.

## Layout

```
  0                                                                          4096
  +--------+--------------+-------------------+-------------------------------+
  | header |   slots ->   |    free space     |            <- cells           |
  |  24 B  | 2 B per cell |                   |                               |
  +--------+--------------+-------------------+-------------------------------+
           24      24 + 2*cellCount     cellAreaStart
```

Slots grow forward. Cells grow backward. The page is full when they meet.

All multi-byte numbers are big-endian, so byte order is the same as numeric order.

### Header, 24 bytes

| Off | Size | Field | Meaning |
|-----|------|-------|---------|
| 0 | 1 | `pageType` | 0 = LEAF, 1 = INTERNAL |
| 1 | 1 | `flags` | reserved, 0 |
| 2 | 2 | `cellCount` | number of live cells, same as the number of slots |
| 4 | 2 | `cellAreaStart` | lowest byte any cell uses. 4096 when the page is empty |
| 6 | 2 | `fragmentedBytes` | dead bytes left above `cellAreaStart` after deletes |
| 8 | 4 | `rightSibling` | page id, 0 (`Page.NO_PAGE`) when there is none |
| 12 | 4 | reserved | 0 |
| 16 | 8 | `lsn` | kept for milestone 6, 0 for now |

### Slot, 2 bytes

Slot `i` is at `24 + 2*i`. It holds one u16: the offset of its cell inside the page. That is all it
holds. No length, no key.

### Cell

```
[varint keyLength][varint valueLength][key bytes][value bytes]
```

Varints are LEB128. A length under 128 takes one byte, which is the normal case.

The encoding is canonical, so `varIntSize(value)` gives back the width it was written in. This is
why a cell does not need to store the size of its own length fields.

Internal pages use the same cell format. The child page id goes in the value. So this class knows
nothing about the tree above it.

## Example: a page with three pairs

I insert `cat→meow`, then `ant→hi`, then `dog→woof`. Each cell is placed at the bottom of the free
space when it arrives.

```
off     bytes                            meaning
─────────────────────────────────────────────────────────────────────
0000    00 00                            LEAF, flags 0
0002    00 03                            cellCount = 3
0004    0F E7                            cellAreaStart = 4071
0006    00 00                            fragmentedBytes = 0
0008    00 00 00 00                      rightSibling = none
000C    00 ... 00                        reserved + lsn

0024    0F F0                            slot 0 -> 4080   ant
0026    0F F7                            slot 1 -> 4087   cat
0028    0F E7                            slot 2 -> 4071   dog

0030..0FE6                               free space, 4041 bytes

4071    03 04 64 6F 67 77 6F 6F 66       dog -> woof
4080    03 02 61 6E 74 68 69             ant -> hi
4087    03 04 63 61 74 6D 65 6F 77       cat -> meow
```

Slots read top to bottom: **ant, cat, dog**. Sorted.

Cells read low to high: **dog, ant, cat**. Arrival order.

Both are true at the same time. That is the main idea of the layout.

## How it works

### Reading a cell

`slots.cellOffset(i)` gives the cell offset. Then `CellCodec` reads the cell:

```
4080:  03           keyLength   = 3
4081:  02           valueLength = 2
4082:  61 6E 74     key   = ant
4085:  68 69        value = hi
```

The key starts after both varints. The value starts `keyLength` bytes after the key.

Cell size is `varIntSize(keyLength) + varIntSize(valueLength) + keyLength + valueLength`.

### Search

`binarySearch` searches the slot array. It reads a cell only to get the key it needs to compare.

It returns the slot index if the key is there, or `-(insertionPoint) - 1` if it is not.

Searching for `dog` in the page above:

```
low=0 high=2  middle=1 -> slot 1 = 4087 -> key "cat"  cat < dog  ->  low = 2
low=2 high=2  middle=2 -> slot 2 = 4071 -> key "dog"  equal      ->  return 2
```

Two probes. They touch offset 4087, then 4071. So the search moves backward through the page while
it moves forward through the keys. That is fine. The search never assumes cells are in order. It
only asks which key is at a given offset.

Keys are compared in place with `Bytes.compare(buffer, offset, length, key)`. A probe copies
nothing out of the page.

A range scan binary searches once for the start, then walks slot indexes upward. Slot order is key
order, so a scan is a straight walk of the slot array.

### Insert

`insertCell(slotIndex, key, value)`. The caller passes `-binarySearch(key) - 1`.

1. Check the index. Reject a cell bigger than `MAX_CELL_SIZE` (1016 bytes).
2. Reject if `cellBytes + 2` is more than `freeSpace()`. The page is really full.
3. Check that the key sorts strictly between its two neighbours. This rejects duplicate keys. It
   also catches a wrong index before it quietly breaks every later search.
4. If there is enough space but it is fragmented, call `compact()` first.
5. Put the cell at `cellAreaStart - cellBytes`.
6. Shift slots `[slotIndex, count)` up by one. Start from the highest one, so no slot is
   overwritten before it is copied. Then write the new slot and increase `cellCount`.
7. Lower `cellAreaStart` to the new cell.

The only movement is the slot shift, about 250 bytes on a full page of small records. No record
bytes are moved.

### Delete

`deleteCell(slotIndex)` removes the slot and shifts the rest down. Then it decides what happens to
the cell bytes. There are three cases.

1. **The page is now empty.** Set `cellAreaStart` back to 4096 and `fragmentedBytes` to 0.
2. **The cell was exactly at `cellAreaStart`.** Just raise the pointer. Nothing is wasted.
3. **Anything else.** The bytes are stuck in the middle of the cell area. Add them to
   `fragmentedBytes` and leave them where they are.

Deleting `ant` hits case 3, because `dog` sits below it:

```
0002    00 02        cellCount = 2
0004    0F E7        cellAreaStart = 4071   (not changed)
0006    00 07        fragmentedBytes = 7    <- ant's cell, now dead

0024    0F F7        slot 0 -> 4087   cat
0026    0F E7        slot 1 -> 4071   dog

4080    .. .. ..     7 dead bytes, still there
```

Those 7 bytes cannot be used again until a compaction. If I insert `bee→zz` next, it takes 7 fresh
bytes at 4064 and the dead ones stay where they are.

### Compact

`compact()` slides all live cells together, so the free space becomes one single run.

It never creates free space. `freeSpace()` is the same before and after. It only makes the space
usable.

1. Add up the sizes of the live cells.
2. Copy each cell into a scratch array in slot order, and rewrite each slot to its new offset.
3. Write the scratch array back so it ends at 4096.
4. Set `cellAreaStart` to the new value and `fragmentedBytes` to 0.

I copy through a scratch array instead of sliding in place because the source and target ranges
overlap in ways that are hard to get right.

Compacting the page after `ant` was deleted:

```
0002    00 02                            cellCount = 2
0004    0F EE                            cellAreaStart = 4078
0006    00 00                            fragmentedBytes = 0

0024    0F EE                            slot 0 -> 4078   cat
0026    0F F7                            slot 1 -> 4087   dog

4078    03 04 63 61 74 6D 65 6F 77       cat -> meow
4087    03 04 64 6F 67 77 6F 6F 66       dog -> woof
```

Free space was 4050 before (4043 gap + 7 dead). It is 4050 after, in one piece.

Packing in slot order also puts sorted keys at rising offsets. That is only a side effect. The next
insert goes to the lowest offset and breaks it again, so nothing should depend on it.

### Free space

Two header fields are enough:

```
gap       = cellAreaStart - (24 + 2*cellCount)     one single run
freeSpace = gap + fragmentedBytes                  everything reusable
```

And this always holds:

```
24 + 2*cellCount + gap + liveCellBytes + fragmentedBytes == 4096
```

`hasSpaceFor` checks against `freeSpace`, not `gap`. So if it says yes, `insertCell` will succeed.
It may compact on its own first. The caller cannot see fragmentation and does not need to think
about it.

## Limits and errors

| | |
|---|---|
| Usable bytes | 4072 (4096 − 24 header) |
| Biggest cell | 1016 bytes, so 4 cells always fit and fanout cannot collapse |
| Cells per page | 254 at k=8 v=4 · 33 at k=16 v=100 · 169 children at k=16 |
| Cell too big, wrong sort order, duplicate key, bad buffer | `IllegalArgumentException` |
| Page full | `IllegalStateException` |
| Slot index out of range | `IndexOutOfBoundsException` |

A duplicate key is rejected, not overwritten. An update is a delete plus an insert.

## Classes

Four classes. Each one owns a different part of the byte layout.

| Class | Owns |
|---|---|
| `PageHeader` | The header fields. The only file with header offsets in it |
| `SlotDirectory` | The slot array: read, write, shift, and its own length |
| `CellCodec` | The cell format. Static, because a cell is a layout, not an object |
| `SlottedPage` | Search, placement, free space |

`SlotDirectory` owns `cellCount`, even though the number sits in the header. So `insert` and
`remove` shift the slots and update the count in one step.

## Tests

`SlottedPageTest` (10) and `BytesTest` (8). Two of them do most of the work.

**Fill, delete every second cell, compact, read back.** Inserts come in shuffled order, so slots
shift in the middle instead of only appending. It checks that free space after the deletes equals
the sum of the deleted cell sizes, that `compact()` does not change `freeSpace()`, and that the
space really is usable again.

**200 random operations against a `TreeMap`** with an `Arrays.compareUnsigned` comparator. It mixes
insert, update (delete plus insert) and delete, and compacts every 50 operations. `cellCount` is
compared with the model after every operation, and the full key/value list is compared at the end.
Seed is `20260815`, so a failure can be reproduced.

The rest cover `binarySearch` insertion points, sort order and duplicate rejection, empty keys and
values, the biggest allowed cell fitting exactly 4 times, `wrap` reading back a page through
another buffer view, and `init` not touching the caller's position and limit.

## What is missing

| Missing | What it costs |
|---|---|
| Overflow pages | Cells over 1016 bytes are rejected. I cannot store big values yet |
| Free list | Deleted bytes come back only through `compact()`, so delete-heavy work compacts often |
| In-place compaction | `compact()` allocates a scratch array every time |
| `leftSibling` | A backward range scan has to go through the parent |
| Checksum | A torn or corrupt page is not detected. Milestone 6 |
| Latches | Not thread safe. Milestone 7 |
