# 1. Slotted pages

A `SlottedPage` stores sorted key/value pairs in a 4096-byte buffer.
It does no file I/O. Keys and values have variable lengths, subject to the cell-size limit below.

A **cell** contains a key and its value. A **slot** is a two-byte pointer to a cell.
Slots stay in key order, so inserting a record usually moves only slots.
Compaction can move the cells themselves.

## Page layout

```text
0         24                                               4096
+---------+------------+------------------+--------------------+
| header  | slots --->|    free gap      |<--- cells          |
+---------+------------+------------------+--------------------+
```

Slots grow toward the end of the page. Cells are placed from the end backward.
Only slot order determines key order.

### Header

Offsets are decimal. Fixed-width fields use big-endian byte order.

| Offset | Bytes | Field | Meaning |
|---|---|---|---|
| 0 | 1 | pageType | 0 = leaf, 1 = internal |
| 1 | 1 | flags | Reserved |
| 2 | 2 | cellCount | Number of live cells |
| 4 | 2 | cellAreaStart | Start of the cell area; 4096 when empty |
| 6 | 2 | fragmentedBytes | Deleted cell bytes inside that area |
| 8 | 4 | rightSibling | Next leaf page; 0 means none |
| 12 | 4 | reserved | Unused |
| 16 | 8 | lsn | Reserved for future durability work |

### Slots and cells

Slot `i` starts at `24 + 2*i`. It stores the cell's offset as an unsigned 16-bit number.

```text
cell = [varint key length][varint value length][key][value]
```

An internal page uses the same format, with a four-byte child page ID as the value.

## Example

Insert `cat -> meow`, `ant -> hi`, then `dog -> woof`.
Offsets below are decimal; byte values are hexadecimal.

| Offset | Bytes | Meaning |
|---|---|---|
| 2 | 00 03 | Three cells |
| 4 | 0F E7 | Cell area starts at 4071 |
| 24 | 0F F0 | Slot 0 points to ant at 4080 |
| 26 | 0F F7 | Slot 1 points to cat at 4087 |
| 28 | 0F E7 | Slot 2 points to dog at 4071 |
| 4071 | 03 04 64 6F 67 77 6F 6F 66 | dog -> woof |
| 4080 | 03 02 61 6E 74 68 69 | ant -> hi |
| 4087 | 03 04 63 61 74 6D 65 6F 77 | cat -> meow |

The slots list **ant, cat, dog**, even though the cells are elsewhere in the page.
The free gap runs from offset 30 through 4070: 4041 bytes.

## Search and insert

Search uses binary search over the slots and compares keys directly in the buffer.
It returns the matching slot, or `-insertionPoint - 1` if the key is absent.

To insert:

1. Check the slot index, cell size, available space, and key order.
2. Compact the page if the total free space is enough but the gap is too small.
3. Write the cell immediately before the current cell area.
4. Shift later slots and add the new pointer.

Duplicate keys are rejected by the page. The tree implements replacement by deleting the old
cell and inserting the new one.

## Delete and compact

Deletion removes a slot. What happens to the cell space depends on its location:

| Case | Action |
|---|---|
| Last live cell removed | Reset the cell area to empty |
| Cell starts at cellAreaStart | Move cellAreaStart past it |
| Cell is elsewhere | Count its bytes as fragmented space |

Deleting `ant` from the example leaves seven fragmented bytes.
There are now two slots, a 4043-byte gap, and seven fragmented bytes: 4050 free bytes in total.

Compaction copies live cells through a scratch array and places them together at the end.
It updates the slots and clears the fragmentation count.
In this example, `cat` moves to 4078 and `dog` to 4087. Free space remains 4050 bytes.

## Space accounting

```text
gap = cellAreaStart - (24 + 2 * cellCount)
freeSpace = gap + fragmentedBytes

24 + 2 * cellCount + gap + liveCellBytes + fragmentedBytes = 4096
```

A cell uses its key, value, and both length fields. An entry also needs a two-byte slot.

| Limit | Value |
|---|---|
| Usable page space | 4072 bytes |
| Maximum cell size | 1016 bytes |
| Maximum entry size, including slot | 1018 bytes |

The size limit allows four maximum-size entries to fit.
Larger records need overflow pages, which are not implemented.

## Classes and limits

| Class | Responsibility |
|---|---|
| PageHeader | Header fields |
| SlotDirectory | Slots and cell count |
| CellCodec | Cell encoding and reading |
| SlottedPage | Search, insert, delete, compact, and reset |
| PageBuffers | Shared buffer validation and views |
| Page / PageType | Page constants and types |

Bad arguments or duplicate keys are rejected. A full page throws `IllegalStateException`;
an invalid slot throws `IndexOutOfBoundsException`.

There are no checksums, backward sibling links, or thread-safety guarantees.
Wrapping an existing page checks its type, but does not fully validate its contents.
