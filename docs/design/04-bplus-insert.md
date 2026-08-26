# 4. B+Tree insert

Read and write a key/value pair through a tree of pages. Splitting is chosen but not yet wired up,
so a full leaf still throws. Delete is not in this milestone.

## What it is

`SlottedPage` holds one 4 KB block. This layer turns many of those blocks into one sorted map.

Two page types, one cell format:

```
LEAF      key -> value            the actual data
INTERNAL  key -> child page id    only signposts
```

All data lives in the leaves. A key in an internal page is a router, not a record. It does not have
to exist as a record anywhere below it.

## Why it is built this way

I am building milestone 4 before milestones 2 and 3. The Pager and BufferPool do not exist, and the
tree is the part I actually want to get right, so I put an interface between them.

```
PageStore
   allocate(type) -> new page id, already formatted
   get(pageId)    -> borrow it
   release(pageId)-> give it back
```

`HeapPageStore` is a `HashMap` and lives in the test sources. The BufferPool implements the same
three methods later and the tree does not change.

Two rules the store must follow:

1. Page ids start at 1. `Page.NO_PAGE` is 0, so handing out page 0 would make "no sibling" and
   "page 0" the same value.
2. `get` and `release` pair up. `HeapPageStore` counts them so a test can prove the tree gives
   every page back.

## Layout

An internal node with 3 children needs only 2 separators, so one child pointer has no key.

```
        [  60    100  ]      2 separators
        /      |      \
      c0      c1      c2     3 children
```

I keep `c0` in a cell with an **empty key**. Empty keys are legal in a page and sort first, so it
lands in slot 0 and `binarySearch` finds it with no special case. Nothing in the page format had to
change.

```
slot 0   ""   -> c0      leftmost child
slot 1   60   -> c1      keys 60..99 go here
slot 2   100  -> c2      keys 100 and up go here
```

The child page id is the cell's value, 4 bytes big-endian.

## Example: an internal page

Leftmost child is page 7, then `60 -> page 12` and `100 -> page 19`. Written in that order, so the
cells sit in arrival order while the slots stay sorted.

```
off     bytes                                  meaning
──────────────────────────────────────────────────────────────────────
0000    01 00                                  INTERNAL, flags 0
0002    00 03                                  cellCount = 3
0004    0F E6                                  cellAreaStart = 4070
0006    00 00                                  fragmentedBytes = 0
0008    00 00 00 00                            rightSibling = none

0024    0F FA                                  slot 0 -> 4090   ""   leftmost
0026    0F F0                                  slot 1 -> 4080   60
0028    0F E6                                  slot 2 -> 4070   100

4070    04 04 00 00 00 64 00 00 00 13          100 -> page 19
4080    04 04 00 00 00 3C 00 00 00 0C          60  -> page 12
4090    00 04 00 00 00 07                      ""  -> page 7
```

The leftmost cell is the trick made concrete. Its first byte is `00`, a key length of zero, so it
sorts before every separator and lands in slot 0. It costs 6 bytes: two length varints and the
4-byte page id.

## How it works

### Finding the leaf

Both `get` and `put` start the same way. Walk down until the page type is LEAF.

```
idx = page.binarySearch(key)

idx >= 0  ->  follow slot idx            exact hit on a separator
idx <  0  ->  follow slot (-idx-1) - 1   the separator just below
```

The empty key in slot 0 is what makes this total. A key smaller than every separator gives
insertion point 1, so it follows slot 0. An empty search key matches slot 0 exactly, which is also
right, because the empty key is the smallest key there is.

The parent is released before the child is taken. A read never splits, so it never needs to hold
both.

Descent stops after 64 levels. Fanout is at least 2, so a real tree is nowhere near that deep.
Hitting it means a child pointer forms a cycle, and I would rather see an exception than watch a
test hang.

### Reading

`binarySearch` the leaf. Index 0 or above returns the value, below zero returns null.

### Writing

```
cellSize(key, value) > MAX_CELL_SIZE  ->  throw, before any descent
binarySearch >= 0                     ->  replace
binarySearch <  0                     ->  insertCell at -index-1
```

`insertCell` rejects duplicate keys, so replacing is a delete followed by an insert. The order
matters:

```
1. work out what the delete would free and what the new cell needs
2. if it still would not fit, throw and delete nothing
3. deleteCell(index)
4. insertCell(index, key, value)
```

Step 2 is the point. Without it, growing a value on a nearly full page throws away a record I
cannot put back. The insert goes back at the same index, because deleting shifts the slots above
down one and leaves exactly the gap the key belongs in.

### Choosing where to split

`SplitPolicy` takes the sizes of the entries the page will hold after the insert, in key order, and
returns the index where the right half starts.

Two things the caller has to get right. Each entry costs `cellSize + SLOT_SIZE`, not just the cell.
And the list includes the new entry, not just what is on the page now.

Splitting at half the count is wrong. Cells vary by hundreds of bytes, so half the count can be
nearly all the bytes:

```
sizes:  20  20  20  20  1018 1018 1018 1018 1018      capacity 4072

by count (cut at 4):   left  80     right 5090    does not fit
by bytes (cut at 6):   left 2116    right 3054    fits
```

So I walk the running total to the first point it reaches half, then look at the cut just before
and the cut just after. Both are rejected if either side is over capacity or empty. The more
balanced survivor wins.

```
sizes:    309   909   259   909   809   409   709     total 4313, half 2156
running:  309  1218  1477  2386  3195  3604  4313
                            ^ first crossing

cut at 3:  left 1477   right 2836   imbalance 1359
cut at 4:  left 2386   right 1927   imbalance  459   <- chosen
```

Notice neither the counts (4 and 3) nor the bytes come out even. That is fine.

**Only those two cuts can ever win.** The cut at the crossing always has a left side under half, so
it always fits. Moving to a smaller index shrinks the left and grows the right, which is the side
that was already too big. Moving to a larger index pushes the left over capacity. So there is no
third candidate to try. My plan said to walk outward when both fail; that walk is dead code and I
dropped it.

When both fail it means the entries need more than two pages, and that is a throw, not a retry.

Capacity is an argument rather than `SlottedPage.USABLE_BYTES`, so the class is a pure function and
the tests can use a capacity of 100 with two-digit sizes.

## Classes

| Class | Owns | Where |
|---|---|---|
| `PageStore` | Where pages come from | main |
| `InternalNode` | The internal page format: separators, children, routing | main |
| `SplitPolicy` | Where to cut a page. Pure arithmetic | main |
| `BPlusTree` | Descent, get, put | main |
| `HeapPageStore` | An in-memory `PageStore` | test |
| `TreeInvariants` | Checks the tree is still a tree | test |
| `TreeFixture` | Builders shared by the tests | test |

## Limits and errors

| | |
|---|---|
| Longest separator I can promote | about 1009 bytes (`MAX_CELL_SIZE` 1016, minus the varints and the 4-byte child id) |
| Pair too big for one cell | `IllegalArgumentException`, naming overflow pages |
| Leaf full | `IllegalStateException`, until splitting is wired up |
| Duplicate separator, or an empty key used as one | `IllegalArgumentException` |
| Child pointers form a cycle | `IllegalStateException` after 64 levels |
| No cut fits | `IllegalStateException` from `SplitPolicy` |

A duplicate key is replaced, not rejected. That is the one place the tree is softer than the page
under it.

## What is missing

| Missing | What it costs |
|---|---|
| Splitting is not wired up | A full leaf throws. `SplitPolicy` is written and tested but nothing calls it yet |
| Delete | Not in this milestone |
| Overflow pages | A pair over 1016 bytes is rejected |
| A meta page | The root page id lives in a field, not on disk |
| Latch crabbing | Single threaded. The descent releases the parent early, which is the right shape for it, but there are no latches yet |
