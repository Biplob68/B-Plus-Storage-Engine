# 4. Tree lookup and insertion

`BPlusTree` combines pages into a sorted map of byte-array keys and values.
Leaf pages hold records. Internal pages hold keys that direct searches to child pages.

`get(key)` returns the value, or null if the key is missing.
`put(key, value)` inserts a record or replaces the value for an existing key.

Deletion and scans are covered in [deletion](04-bplus-delete.md) and [range scans](04-bplus-scan.md).

## Where pages come from

The tree uses `PageStore`:

| Operation | Purpose |
|---|---|
| allocate(type) | Create a formatted page and return its ID |
| get(pageId) | Borrow a page |
| release(pageId) | Return a borrowed page |
| free(pageId) | Release an unreachable page |

Page IDs start at 1. Zero means no page in tree links.
Every successful borrow must have a matching release.

`HeapPageStore` holds pages in memory for tests. `BufferPool` implements the same interface over
`Pager` for file storage. Shared contract tests exercise both stores.

Use [Database.open(path)](03-database.md) for the full file-backed engine. It opens the root already
reserved by the pager. `BPlusTree.create(store)` allocates a separate new root and does not update
file metadata.

## Internal page layout

A **separator** is a key that marks the start of a child's search range.

```text
slot 0: empty key -> page 7     keys below 60
slot 1: 60        -> page 12    keys from 60 up to 100
slot 2: 100       -> page 19    keys from 100 onward
```

The empty key in slot 0 represents the leftmost child.
Other cells store a separator as the key and a four-byte child ID as the value.
Separators guide searches; they are not data records and may remain after a record is deleted.

### Byte example

Here the numeric keys use four-byte big-endian encoding.
Offsets are decimal and bytes are hexadecimal.

| Offset | Bytes | Meaning |
|---|---|---|
| 24 | 0F FA | Slot 0 points to 4090 |
| 26 | 0F F0 | Slot 1 points to 4080 |
| 28 | 0F E6 | Slot 2 points to 4070 |
| 4070 | 04 04 00 00 00 64 00 00 00 13 | 100 -> page 19 |
| 4080 | 04 04 00 00 00 3C 00 00 00 0C | 60 -> page 12 |
| 4090 | 00 04 00 00 00 07 | Empty key -> page 7 |

The page is internal, has three cells, and its cell area starts at 4070.

## Finding a key

Start at the root. On each internal page, follow the greatest separator less than or equal
to the key. If the key is smaller than every separator, follow slot 0.

At the leaf, use binary search to find the record.
Read operations release each parent before borrowing the child.

Descent is limited to 64 levels to avoid running indefinitely on cyclic child pointers.

## Inserting or replacing

1. Reject a key/value cell larger than 1016 bytes.
2. Follow the search path to its leaf.
3. If the key exists, delete its old cell.
4. Insert the new cell if it fits; otherwise split the leaf.
5. Add any new separator to the parent, splitting the parent if needed.

Insertions hold parent pages while descending because they may need to update them afterward.
The operation does not provide rollback if allocation or another store operation fails.

## Choosing a split

Entries are divided by byte size, including the two-byte slot for each entry.
Dividing by record count could leave one page too large.

```text
entry sizes: 309, 909, 259, 909, 809, 409, 709
total: 4313 bytes

cut after 3 entries: left 1477, right 2836
cut after 4 entries: left 2386, right 1927  <- more balanced
```

`SplitPolicy` checks the cuts on either side of the halfway byte total.
It chooses the most balanced cut where both pages fit and neither is empty.
If neither fits, it throws. Moving farther from halfway cannot improve the balance.

## Splitting a leaf

Read the entries, add the new record in key order, and choose a cut.
Allocate the right page, then rebuild both halves.

```text
before: left [20, 40, 60, 80], insert 50
after:  left [20, 40, 50] -> right [60, 80]

parent receives separator 60
record 60 stays in the right leaf
```

This diagram shows the shape; actual cuts depend on entry sizes.

The new right leaf inherits the old next-leaf link.
The left leaf then points to the new right leaf.
This keeps range scans connected.

## Splitting an internal page

An internal split moves the separator at the cut up to the parent.
Its child becomes the right page's leftmost child.

```text
before: empty->a | 40->b | 60->c | 70->d | 100->e

after:
left:   empty->a | 40->b | 60->c
right:  empty->d | 100->e
parent receives 70
```

Unlike a leaf split, the promoted separator is removed from the two child pages.
Replacing its key with an empty key also makes the right half smaller than the split estimate.

## Growing the root

A split can travel up through several parents.
If it reaches the root, `RootSplit` adds a level while keeping the root page ID:

1. Copy the root's current left-half contents to a new page.
2. Reset the root as an internal page.
3. Point it at that copied left page and the new right page.

Every leaf becomes one level deeper, so the tree stays balanced.

## Main classes and limits

| Class | Responsibility |
|---|---|
| BPlusTree | Public operations and descent |
| InternalNode | Child routing and separators |
| SplitPolicy | Split position by bytes |
| LeafSplit / InternalSplit | Rebuild two pages |
| RootSplit | Add a root level |
| SplitResult | Separator and right-page ID returned to the parent |

Oversized cells and invalid separators are rejected.
A separator must also fit in a cell with its four-byte child ID; the maximum separator key
length is 1009 bytes. A leaf record fitting alone does not guarantee its key can be promoted.

Overflow pages, rollback, crash durability, and concurrent access are not implemented.
