# 0. Bytes

Not a milestone. It is the shared foundation both the page layer and the tree layer sit on, so it
gets its own doc.

## What it is

Three things:

1. Unsigned comparison of `byte[]`, which is the engine's only key ordering.
2. LEB128 varints, used for the lengths inside a cell.
3. Fixed 4-byte big-endian ints, used for a child page id inside an internal cell.

Every method that touches a `ByteBuffer` uses absolute indexing, so a page's position and limit are
never disturbed.

## Why it is built this way

**Java bytes are signed.** A key is `byte[]`, and I need `0x80` to sort above `0x7F`. Comparing
signed would put it below.

**Lengths are usually small.** A key is normally a few bytes and a value a few hundred. A fixed
2-byte length field would waste a byte on almost every cell. A varint spends one byte while the
length is under 128, which is the common case.

**Page ids are not small and not variable.** A child pointer is always 4 bytes, so it does not need
a varint. Big-endian keeps it consistent with the page header.

## Layout

A varint holds seven payload bits per byte, low group first. The high bit is set on every byte
except the last.

| Value under | Bytes |
|---|---|
| 2^7 | 1 |
| 2^14 | 2 |
| 2^21 | 3 |
| 2^28 | 4 |
| anything larger | 5 |

A fixed int is always 4 bytes, most significant first, so byte order is the same as numeric order.

## Example

Three varints:

```
127   ->  7F              1 byte
128   ->  80 01           2 bytes
300   ->  AC 02           2 bytes
```

Working out 300: the low seven bits are `0101100` = `0x2C`. Set the high bit to say "more coming"
and that is `0xAC`. Then `300 >>> 7` is 2, so `0x02` with the high bit clear ends it.

Two fixed ints:

```
encodeInt(0x01020304)  ->  01 02 03 04
encodeInt(4096)        ->  00 00 10 00
```

And a whole cell header, showing why the widths never need storing:

```
4080:  AC 02        keyLength = 300,  varIntSize(300) = 2  ->  next field at 4082
4082:  05           valueLength = 5,  varIntSize(5)   = 1  ->  key starts at 4083
4083:  ...          300 key bytes
4383:  ...          5 value bytes
```

## How it works

### Unsigned comparison

```
0x80  vs  0x7F      signed:   -128 < 127     wrong
                    unsigned:  128 > 127     right
```

A prefix sorts before the longer array:

```
{1, 2}  vs  {1, 2, 0}      ->  {1, 2} first
{}      vs  {0}            ->  {} first
```

The empty array is a real key, and it is the smallest key there is. The tree relies on that: an
internal page keeps its leftmost child under an empty key so it always lands in slot 0.

There are two forms:

```
compare(byte[] a, byte[] b)
compare(ByteBuffer buf, int index, int length, byte[] other)
```

The second exists so `binarySearch` can compare a stored key in place. A probe copies nothing out
of the page.

### The canonical property

This is the part that matters. The encoding is canonical, so `varIntSize(decodedValue)` gives back
the width the value was written in.

That is why a cell does not need a field describing how wide its length fields are. Read a length,
ask how wide it was, step forward. Nothing extra is spent.

### Whole-range round trip

`decodeInt` returns the same 32 bits `encodeInt` was given, including values that come back as a
negative int. Page ids are unsigned carried in a signed `int`, so `0xFFFFFFFF` has to survive.

## Limits and errors

| | |
|---|---|
| Longest varint | 5 bytes |
| Negative value passed to a varint method | `IllegalArgumentException` — lengths are never negative |
| Varint with no terminating byte in 5 | `IllegalStateException` |
| Varint that overflows a signed int | `IllegalStateException` |
| `decodeInt` given other than 4 bytes | `IllegalArgumentException` |

## What is missing

| Missing | What it costs |
|---|---|
| Signed varints (zigzag) | Nothing yet. Only lengths are encoded, and they are never negative |
| Varints longer than 32 bits | An `lsn` will be 8 bytes when milestone 6 needs it, written as a fixed field rather than a varint |
