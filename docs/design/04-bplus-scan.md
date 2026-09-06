# 4. B+Tree range scan

Walk a range of keys in order. Milestone 4 alongside [insert](04-bplus-insert.md) and
[delete](04-bplus-delete.md).

## What it is

`tree.scan()` walks everything. `tree.scan(from, to)` walks `[from, to)`. Null on either side means
unbounded.

```java
Cursor cursor = tree.scan(key(200), key(260));
while (cursor.next()) {
    cursor.key();
    cursor.value();
}
```

## Why it is built this way

This is what the leaf chain is for. Before this, I built the chain on every split, rewired it on
every merge, and checked it in invariant 4 — and nothing read it.

The point is that a range does **one** descent. After that it is a pointer walk:

```
descend once ──▶ ┌───────┐    ┌───────┐    ┌───────┐
                 │ 10 15 │───▶│ 20 30 │───▶│ 50 60 │───▶ NO_PAGE
                 └───────┘    └───────┘    └───────┘
                     ▲ find where the range starts, then never descend again
```

Without the chain, every key in the range would need its own descent from the root. That is the
difference between a B+Tree and a B-tree, and it is why all the data lives in the leaves.

**I buffer one leaf rather than holding one page.** On entering a leaf I read its entries out and
release the page straight away. So a cursor never keeps a page borrowed, needs no closing, and
cannot leak one if a caller walks away half-way. The price is one leaf's entries in memory, at most
4072 bytes. Holding the page instead would have meant an `AutoCloseable` and a way to leak.

## Layout

Nothing new on disk. The cursor's whole state is four fields:

| Field | Holds |
|---|---|
| `buffered` | The current leaf's entries, already copied out |
| `index` | Where in `buffered` the next entry is |
| `nextLeafPageId` | The current leaf's `rightSibling`, or `Page.NO_PAGE` |
| `toExclusive` | The upper bound, or null |

## Example

Three leaves, the middle one emptied by earlier deletes.

```
   page 2        page 5        page 9
 ┌─────────┐   ┌─────────┐   ┌─────────┐
 │  10 20  │──▶│ (empty) │──▶│  50 60  │──▶ NO_PAGE
 └─────────┘   └─────────┘   └─────────┘

 scan()

 load 2   buffered=[10,20]  next=5     ──▶ 10, 20
 load 5   buffered=[]       next=9     ──▶ nothing, go round again
 load 9   buffered=[50,60]  next=NONE  ──▶ 50, 60
 done
```

## How it works

### Starting

```
from == null  ->  descend taking slot 0 all the way down, to the leftmost leaf
from given    ->  descend normally to the leaf that would hold it
```

Then buffer that leaf and step `index` forward to the first key at or after `from`. That is a plain
walk rather than a binary search, because it happens once per scan, not once per row.

A `from` that is not in the tree lands on the next key up, which is what a range wants.

### Stepping

```
next()
   while the buffer is used up:
       no next leaf?  ->  finish
       load the next leaf
   take the entry
   past the upper bound?  ->  finish
   otherwise it is the current one
```

**A loop, not an `if`.** That is the whole reason empty leaves cost nothing. A delete can leave an
empty leaf sitting in the chain, and the loop simply goes round again instead of returning early.
Written as an `if`, the scan would stop dead at the first empty leaf.

### Finishing

Once the range ends, the cursor clears itself: no current entry, empty buffer, no next leaf. So
calling `next()` again is safe and stays false, and `key()` or `value()` throws rather than handing
back a stale entry.

## Classes

| Class | Owns |
|---|---|
| `Cursor` | The walk: buffer a leaf, serve it, follow the chain |
| `BPlusTree.scan` | The one descent that positions it |

## Limits and errors

| | |
|---|---|
| `key()` or `value()` before `next()`, or after it returned false | `IllegalStateException` |
| Modifying the tree while a cursor is open | Not supported |
| Backward scans | Not possible. The chain only points right |

## What is missing

| Missing | What it costs |
|---|---|
| Backward iteration | The page header has `rightSibling` and no left one, so a reverse scan would have to go through the parent |
| Seeking an open cursor | A new range means a new cursor and a new descent |
| Snapshot isolation | A cursor sees whatever the pages hold as it reaches them |
