# 4. Range scans

A scan reads records in key order.

| Call | Range |
|---|---|
| tree.scan() | All records |
| tree.scan(from, to) | Keys greater than or equal to from and less than to |
| Null lower bound | Start at the first key |
| Null upper bound | Continue to the last key |

The range `[from, to)` includes `from` and excludes `to`.

## Example

Assume `tree` contains UTF-8 text keys:

```java
byte[] from = "cat".getBytes(java.nio.charset.StandardCharsets.UTF_8);
byte[] to = "fox".getBytes(java.nio.charset.StandardCharsets.UTF_8);

Cursor cursor = tree.scan(from, to);
while (cursor.next()) {
    byte[] key = cursor.key();
    byte[] value = cursor.value();
    // Use the current record.
}
```

For keys `ant, cat, dog, fox`, this scan returns `cat` and `dog`.

## How it moves through the tree

The cursor descends once to the starting leaf.
After that, it follows each leaf's `rightSibling` link.

```text
root -> starting leaf
        [10, 15] -> [20, 30] -> [50, 60] -> no next page
```

An unbounded scan starts at the leftmost leaf.
A bounded scan finds the leaf for its lower bound, then walks that leaf's entries to the
first key at or above the bound.

## One leaf at a time

When the cursor reaches a leaf, it copies the entries and immediately releases the page.
It then returns the copied entries one at a time.

This means the cursor needs no close call and does not keep a page borrowed between calls.
It buffers one leaf's records, plus Java object and array overhead; its total memory use
is not limited to exactly one page.

There is no new on-disk layout. The cursor keeps a buffer of entries, the next entry index,
the next leaf ID, the upper bound, and the current entry.

## Empty leaves and stopping

An empty leaf does not end a scan:

```text
page 2 [10, 20] -> page 5 [] -> page 9 [50, 60]
result: 10, 20, 50, 60
```

The cursor continues until there is no next leaf or a key reaches the excluded upper bound.
Then it clears its current entry and returns false.
Later calls to `next()` also return false.

## Classes and limits

| Item | Behavior |
|---|---|
| BPlusTree.scan | Finds the starting leaf |
| Cursor | Buffers entries and follows leaf links |
| key() or value() without a current entry | IllegalStateException |
| Tree changes during a scan | Not supported |
| Backward scans or seeking an existing cursor | Not implemented |
| Snapshot isolation | Not implemented |

The upper-bound array is retained by reference. Keep it unchanged while scanning.
Leaf links are trusted; the cursor does not detect sibling cycles.
