# Design docs

One doc per feature. I write it after the feature is done and the tests are green, so it describes
what I actually built, not what I planned to build.

The numbers match the milestones. Number 0 is the shared foundation both layers sit on, not a
milestone of its own.

| # | Feature | Status | Doc |
|---|---------|--------|-----|
| 0 | Bytes | Done | [00-bytes.md](00-bytes.md) |
| 1 | SlottedPage | Done | [01-slotted-page.md](01-slotted-page.md) |
| 2 | Pager | Done | [02-pager.md](02-pager.md) |
| 3 | BufferPool | Done | [03-buffer-pool.md](03-buffer-pool.md) · [03-database.md](03-database.md) |
| 4 | BPlusTree | Done | [04-bplus-insert.md](04-bplus-insert.md) · [04-bplus-delete.md](04-bplus-delete.md) · [04-bplus-scan.md](04-bplus-scan.md) |
| 5 | Oracle harness | Not started | — |
| 6 | COW durability | Not started | — |
| 7 | Latch crabbing | Not started | — |
| 8 | Benchmarks | Not started | — |

## What goes in a doc

What the feature is, and how it works. Enough that I can follow it later without reading the
source.

That means the byte layout, what each operation does step by step, and a worked example with real
bytes and real offsets. Layouts are slow to work out again from code. Examples catch the things a
description hides.

Keep everything else short. Limits, errors, missing pieces: one table each.

Leave out anything the code already says. No walking through methods one by one, no repeating
method signatures in words, no long argument about designs I did not pick.

Write in plain, short sentences.

## Template

```markdown
# N. <Feature>

## What it is
Two or three sentences.

## Why it is built this way
The problem that forced this shape. Keep it short.

## Layout
Diagram, field table, formats.

## Example
Real bytes at real offsets.

## How it works
One section per operation. The steps, the cost, and the bytes before and after where it helps.

## Classes
Which class owns which part.
```
