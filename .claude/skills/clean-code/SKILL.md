---
name: clean-code
description: Refactor Java in this repo for meaningful naming, small methods, single responsibility, and comments that earn their place. Use when asked to refactor, clean up, tidy, or improve naming, and as a self-check before finishing any commit that adds code.
---

# Clean code in bplus-engine

Apply these on every commit, not only when a refactor is asked for.

## Naming

**Never name a variable after its type when it holds something else.** The worst bug this repo has
produced was a variable called `cell` that held an offset:

```java
int cell = cellOffset(slotIndex);   // reads as a Cell object
int cellOffset = ...;               // says what it is
```

- A byte offset ends in `Offset`. A count ends in `Count`. A size in bytes ends in `Bytes` or `Size`.
- Say what a thing is, not what it looks like. `gap()` became `contiguousFreeSpace()`.
- A guard says which rule it enforces. `requireStorable` became `requireFitsInOneCell`.
- No single letters outside a tight loop index. `n` → `count`, `p` → `offset`, `lo`/`hi`/`mid` →
  `low`/`high`/`middle`.
- Method names carry the ordering rule where one exists: `openSlot` / `closeSlot`,
  `linkSiblings`, `rewriteEntries`.

## Small methods, one job each

- If a method reads a page, decides something, and writes it back, split it.
- Extract a nested `try/finally` into a named method rather than nesting two of them.
- Extract a repeated expression into a named local, especially when it appears in both a condition
  and its error message.
- A parameter list past four usually means a missing type. Two loose `byte[]` bounds became a
  `Bounds` record with `isBelowRange` / `isAboveRange`.

## Single responsibility

Split by reason to change, not by line count.

- `SlottedPage` owned header offsets, slot mechanics, cell framing and space policy at once. It
  became `PageHeader`, `SlotDirectory`, `CellCodec` and `SlottedPage`.
- Put a fact where it belongs, even if a caller elsewhere is more convenient. `entrySize` sat on
  `BPlusTree` but is page accounting, so it moved to `SlottedPage` next to `cellSize`.
- Give a component its whole job. `SlotDirectory` owns `cellCount` even though the number lives in
  the header, so `insert`/`remove` shift the slots and update the count in one step.
- Stateless things stay static. A cell is a layout, not an object — making it one would allocate on
  every binary-search probe.

## Comments

Write only what the code cannot say:

- **Why**, not what. "Read L's old value first, or the chain loops back on itself."
- Ordering rules, invariants, and byte layouts.
- A deliberate trade-off, in one line.

Delete on sight: comments restating the method name, second clauses that repeat the first, and
section banners with no content under them.

## Dead code

Delete it rather than keeping it as insurance.

- Unused members go, even if a later commit might want them (`SlottedPage.page()`).
- A check that cannot fire goes. `TreeInvariants` had a "slots are sorted" rule that `insertCell`
  already made impossible.
- If a plan step turns out to be unreachable, say so and drop it — `SplitPolicy`'s outward walk.

## Tests

- Extract shared builders. Four copies of `key(int)` became `TreeFixture`.
- A test that could pass for the wrong reason must first assert the wrong thing is wrong:
  `balancesBytesWhereBalancingCountWouldOverflow` proves the count split really would overflow
  before checking the byte split does not.
- Name the behaviour, not the method. `theNewLeafTakesOverTheOldRightSibling`, not `testSplit`.
- Seeded randomness only, so a failure reproduces.

## Before finishing

1. `./gradlew test` green.
2. Every new name re-read once: does it say what the thing is?
3. Every new comment re-read once: does the code already say this?
4. Anything unused, delete.
