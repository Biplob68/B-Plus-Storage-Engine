# 4. B+Tree delete

Remove a key, and put the tree back in shape afterwards. Milestone 4 alongside
[insert](04-bplus-insert.md).

## What it is

`delete(key)` returns true when the key was there. Removing the cell is the easy part. The work is
what happens after: a page that has lost too much has to be pooled with its sibling, and that can
cascade all the way up to the root.

## Why it is built this way

Every piece of delete is a piece of insert run backwards.

| grow | shrink |
|---|---|
| page overflows | page **underflows** |
| split into two | pool with a sibling: **merge**, or cut in two again |
| push a separator **up** to the parent | pull a separator **down** from the parent |
| root gains a level | root **loses** a level |

So I built each one as the mirror of the class that already existed, and reused the pieces. The
clearest case: **redistributing is a split**. Pool the two pages' contents and hand them to
`SplitPolicy`. There is no second policy and no separate "borrow one entry" rule.

The one thing that is not symmetric: a split only ever looks at one page. A merge has to look at a
**sibling**, and a sibling must share the same parent. Two leaves that sit next to each other on the
chain but hang off different parents cannot be merged, because no single separator divides them.
That is why delete descends recursively and holds the parent, the way insert does, instead of
releasing it like a read.

## Layout

Nothing new on disk. Two numbers decide everything:

| Rule | Value |
|---|---|
| A page is underflowed when | `usedBytes * 3 < USABLE_BYTES`, so under about 1357 of 4072 bytes |
| Two pages merge when | their pooled contents are `<= USABLE_BYTES` |

**A third, not a half.** This one caught me out. A byte-balanced split leaves both halves at roughly
50%, so with a half-full threshold a page would be eligible to merge back the instant it was split,
and the tree could oscillate: split, merge, split. The gap between the two thresholds is what stops
that. A test failure is what made me notice.

Counting bytes rather than cells, because cells vary in size. Half the cells can be nearly all the
bytes.

## Example: pulling a separator down

Two internal nodes merging. The separator that divides them lives in the parent, and it has to come
back down, because the right node's leftmost child has no key of its own.

```
before   root:  leftmost -> L,  50 -> R
         L:     leftmost -> 7,  30 -> 12
         R:     leftmost -> 19, 70 -> 23

after    root:  leftmost -> L                    only one child left
         L:     leftmost -> 7,  30 -> 12,  50 -> 19,  70 -> 23
                                           ^^ came down from the root
         R freed
```

The merged L, byte for byte:

```
off     bytes                                  meaning
──────────────────────────────────────────────────────────────────────
0000    01 00                                  INTERNAL, flags 0
0002    00 04                                  cellCount = 4
0004    0F DC                                  cellAreaStart = 4060
0006    00 00                                  fragmentedBytes = 0

0024    0F FA                                  slot 0 -> 4090   ""   leftmost
0026    0F F0                                  slot 1 -> 4080   30
0028    0F E6                                  slot 2 -> 4070   50
002A    0F DC                                  slot 3 -> 4060   70

4060    04 04 00 00 00 46 00 00 00 17          70 -> page 23
4070    04 04 00 00 00 32 00 00 00 13          50 -> page 19    <- the pulled-down key
4080    04 04 00 00 00 1E 00 00 00 0C          30 -> page 12
4090    00 04 00 00 00 07                      ""  -> page 7
```

The cell at 4070 is the whole point. Before the merge, page 19 sat in R under an empty key and `50`
lived in the root. After it, `50` is a real separator again and the root is one child shorter.

## How it works

### Removing the key

Descend to the leaf, `binarySearch`, `deleteCell`. That alone is safe on its own: no page is
removed, so depths, key order, subtree ranges and the sibling chain are all untouched. A leaf can
be left empty, which wastes space and breaks nothing.

A key that a separator names can be deleted like any other. Afterwards the separator still routes
correctly and simply names a key that exists nowhere:

```
root:  ... 60 -> R ...          root:  ... 60 -> R ...     separator stays
R:     [ 60  75  90 ]     ->    R:     [ 75  90 ]          but no key 60 exists
```

An internal key is a signpost, not a record. After any run of deletes this is the normal state.

### Rebalancing a leaf

On the way back up, the parent checks the child it just descended into. If it is underflowed, pool
it with the sibling beside it — the right one normally, the left one when the child is the last.

```
pooled fits in one page?
    yes  ->  merge:        everything into the left leaf
                           left.rightSibling = right.rightSibling
                           parent drops the separator, right page is freed
    no   ->  redistribute: SplitPolicy cuts the pooled list again
                           parent's separator moves to the new boundary
```

### Rebalancing an internal node

The same two cases, plus the pull-down. The parent's separator joins the pooled list as the key for
the right node's leftmost child, and on a redistribute the entry at the cut goes back up to replace
it.

### Collapsing the root

Merges shrink the root like any other node. When it drops to a single child, that child's contents
move **into the root page** and the child is freed.

```
┌────────┐
│  root  │          ┌──────────┐
└───┬────┘    ->    │   root   │   <- same page id, now holding the child's contents
    │               └──────────┘
[ one child ]
```

The root keeps its page id, exactly as it does when growing. The loop runs again, because a
collapsed root can be left with one child once more.

Because the level comes off the top, every leaf gets shallower by one at the same moment. Depth is
never adjusted for a single leaf, in either direction. That is what keeps the tree balanced.

### Freeing pages

A merge is the only thing in the engine that ever frees a page, which is why `PageStore.free`
appeared with delete rather than being designed up front. `HeapPageStore` drops it from the map, so
a stale child pointer throws immediately instead of reading a dead page.

## Classes

| Class | Owns |
|---|---|
| `Entry` | A leaf's key/value pair, pooled and rewritten by both split and rebalance |
| `Child` | An internal node's child and the separator in front of it. `asLeftmost()` strips the key |
| `LeafRebalance` | Merge or redistribute two adjacent leaves |
| `InternalRebalance` | The same, plus the pull-down |
| `RootCollapse` | Take a level off, keeping the root's page id |
| `PageCopy` | Copy a page's contents into another. Used growing and shrinking |

## Limits and errors

| | |
|---|---|
| Deleting a missing key | Returns false, writes nothing |
| A child with no sibling | Rebalancing is skipped |
| The new separator will not fit in the parent | Rebalancing is skipped |
| Child pointers form a cycle | `IllegalStateException` after 64 levels |

Both skips are safe. An underflowed page is wasted space, never a wrong answer, so declining to act
is always allowed. That is what makes the escape hatches acceptable rather than a hidden bug.

The new separator can be longer than the one it replaces, because it is a data key rather than the
one that was chosen at split time. That is the case the second skip covers.

## What is missing

| Missing | What it costs |
|---|---|
| Freed pages are not reused | Nothing puts them on a free list yet, so a file would keep growing. That arrives with the Pager |
| Merging across parents | Two neighbouring leaves under different parents never merge, so a little space is left unreclaimed |
| A delete-heavy benchmark | The page layer has no free list either, so deletes fragment a page until the next insert compacts it. Worth measuring before tuning either threshold |
