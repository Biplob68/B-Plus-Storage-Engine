# 0. Byte helpers

`Bytes` provides the shared rules for comparing keys and storing numbers.

## Key order

Keys are byte arrays. Each byte is compared as a number from 0 to 255.

```text
7F sorts before 80
[1, 2] sorts before [1, 2, 0]
[] sorts before every nonempty key
```

If two keys begin with the same bytes, the shorter key comes first.
The empty array is a valid key.

The helpers can compare two arrays, or compare a key directly inside a buffer with an array.
The second form lets page searches avoid copying stored keys.

## Variable-length integers

Key and value lengths use LEB128 variable-length integers, or **varints**.
Small lengths take fewer bytes.

Each byte holds seven bits of the number. Its top bit says whether another byte follows.
The lowest seven-bit group is stored first.

| Value range | Bytes |
|---|---|
| 0 to 127 | 1 |
| 128 to 16,383 | 2 |
| 16,384 to 2,097,151 | 3 |
| 2,097,152 to 268,435,455 | 4 |
| 268,435,456 to 2,147,483,647 | 5 |

```text
127 -> 7F
128 -> 80 01
300 -> AC 02
```

For 300, the lowest seven bits are 44 (`2C`). Setting the top bit gives `AC`.
The remaining value is 2, stored as `02`.

The writer always uses the shortest encoding. For bytes written by this engine,
`varIntSize(decodedValue)` therefore tells the cell reader where the next field starts.
The reader does not fully validate arbitrary or noncanonical encodings.

## Example: cell lengths

A cell with a 300-byte key and a 5-byte value uses 308 bytes.
Here it starts at decimal offset 3700, so it fits inside a 4096-byte page.

| Decimal offset | Hex bytes | Meaning |
|---|---|---|
| 3700 | AC 02 | Key length: 300 |
| 3702 | 05 | Value length: 5 |
| 3703–4002 | Key bytes | 300 bytes |
| 4003–4007 | Value bytes | 5 bytes |

## Fixed-width integers

Child page IDs use four bytes in big-endian order: the most significant byte comes first.

```text
encodeInt(0x01020304) -> 01 02 03 04
encodeInt(4096)       -> 00 00 10 00
```

Encoding and decoding preserve all 32 bits, including negative Java `int` values.
This does not mean the pager accepts negative page IDs.

Buffer operations use absolute offsets. They do not change the caller's position or limit.

## Limits

| Situation | Behavior |
|---|---|
| Negative varint input | IllegalArgumentException |
| No terminating byte within five bytes | IllegalStateException |
| Decoded varint produces a negative int | IllegalStateException |
| Fixed-int input is not exactly four bytes | IllegalArgumentException |
| Signed or 64-bit varints | Not implemented |
