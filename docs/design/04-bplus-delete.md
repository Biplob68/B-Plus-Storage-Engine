# 4. Tree deletion

`delete(key)` removes a record. It returns true if the key existed, or false if it was missing.
After a successful delete, the tree may combine or rebalance pages and shrink the root.

## When a page needs rebalancing

A page is **underflowed** when it uses less than one third of its available space:

```text
usedBytes = 4072 - freeSpace
underflow = usedBytes * 3 < 4072
```

The calculation includes cells and slots. It uses bytes because records have different sizes.
The one-third threshold leaves room between a typical split and a later merge.

Two pages can be combined only when they share a parent.
Being next to each other in the leaf chain is not enough.

## Removing the record

The tree descends to the leaf and deletes the matching cell.
A missing key returns false without changing pages.

Deleting a key does not always require changing a separator:

```text
parent separator: 60 -> right leaf
before: right leaf [60, 75, 90]
after:  right leaf [75, 90]
```

The separator 60 still describes a valid boundary.
It does not have to match a record that currently exists.

## Merge or redistribute

On the way back up, check the changed child.
If it is underflowed, choose its right sibling under the same parent, or its left sibling
if it is the last child.

Combine the two pages' entries into one sorted list, then:

| Combined contents | Action |
|---|---|
| Fit in one page | Merge into the left page and free the right page |
| Need two pages | Redistribute using the same byte-based SplitPolicy as insertion |

For a leaf merge, the left leaf inherits the right leaf's next-leaf link.
The parent removes the right child's separator.

For redistribution, the parent separator changes to match the new boundary.
If the replacement separator will not fit, redistribution is skipped.
Rebalancing is also skipped when there is no sibling under that parent.
These cases may leave a sparse page, but preserve valid search ranges.

## Internal pages need the parent separator

The right internal page stores its leftmost child under an empty key.
When combining it with the left page, that child needs the separator from the parent.

```text
before:
parent: empty -> L | 50 -> R
L:      empty -> 7 | 30 -> 12
R:      empty -> 19 | 70 -> 23

merged L:
        empty -> 7 | 30 -> 12 | 50 -> 19 | 70 -> 23
```

The separator 50 moves down and becomes the key for page 19.
The parent removes its pointer to R, and R is freed.

For four-byte numeric keys, the merged page contains these cells.
Offsets are decimal; bytes are hexadecimal.

| Offset | Bytes | Meaning |
|---|---|---|
| 4060 | 04 04 00 00 00 46 00 00 00 17 | 70 -> page 23 |
| 4070 | 04 04 00 00 00 32 00 00 00 13 | 50 -> page 19 |
| 4080 | 04 04 00 00 00 1E 00 00 00 0C | 30 -> page 12 |
| 4090 | 00 04 00 00 00 07 | Empty key -> page 7 |

Slots at offsets 24, 26, 28, and 30 point to 4090, 4080, 4070, and 4060.

If the combined internal contents need two pages, the separator at the new cut moves back
up to the parent. Its child becomes the right page's leftmost child.

## Shrinking the root

When an internal root has only one child, copy that child's contents into the root and free
the child page. Repeat if the root still has only one child.

The root keeps the same page ID. Each collapse removes one level for every leaf.
An empty tree keeps an empty leaf root.

## Main classes and limits

| Class | Responsibility |
|---|---|
| LeafRebalance | Merge or redistribute leaves |
| InternalRebalance | Merge or redistribute internal pages |
| RootCollapse | Remove unnecessary root levels |
| PageCopy | Copy page contents during root changes |
| Entry / Child | Temporary lists used to rebuild pages |

Merges and root collapse free pages through PageStore.
The test store removes freed pages from its map.
The file store uses `BufferPool`, which drops a freed page's cached frame without writing it back.
It does not reclaim the disk page ID. The pager still appends new pages, so the file does not shrink.

Deletion does not merge across different parents, provide rollback, or support concurrent
modification. Descent stops after 64 levels to guard against cyclic child pointers.
