---
title: File Format
---

# The `.cjfr` file format

This page documents the on-disk layout of a condensed-JFR (`.cjfr`) file: the
byte-level structure, the encoding primitives, the footer, and how the reader
fails on malformed or corrupted input. It is a reference for anyone writing a
reader/writer or debugging a broken file, not something you need to understand to
use the CLI.

## Overall structure

A `.cjfr` file is three consecutive regions:

```
+--------------------------+  offset 0
| uncompressed start header|
+--------------------------+
| compressed main stream   |   (compressed with the header's algorithm; NONE = raw)
+--------------------------+  = footerStart
| footer record (raw)      |   1-byte sentinel + zlib blob + 4-byte LE length
+--------------------------+  = end of file
```

- The **start header** is always uncompressed so a reader can learn the
  compression algorithm before decompressing anything.
- The **main stream** holds the type specifications, the configuration/universe,
  and all event instances. It is wrapped in the compressor named by the header
  (or written raw for `NONE`).
- The **footer** is appended *after* the compressor is closed, in the raw
  underlying stream, so it can be read in O(1) from the tail without decompressing
  the main stream.

## Encoding primitives

| Primitive | Encoding |
| --- | --- |
| Unsigned varint | LEB128, 7 bits per byte, high bit = continuation |
| Signed varint | zig-zag transform (`(v << 1) ^ (v >> 63)`) then unsigned varint |
| String | unsigned varint byte length, then that many UTF-8 bytes |
| Fixed long (`long8`) | 8 bytes, little-endian |

All multi-byte fixed integers are little-endian.

## Start header

Written by `CondensedOutputStream.writeStartString`, read by
`CondensedInputStream.readAndProcessStartString`. Fields in order:

| # | Field | Type | Notes |
| --- | --- | --- | --- |
| 1 | start string | string | always `"CondensedData"` |
| 2 | version | unsigned varint | format version; currently `1` |
| 3 | generator name | string | e.g. `"condensed jfr cli"` |
| 4 | generator version | string | e.g. `"0.1"` |
| 5 | generator configuration | string | the condenser config **name** only (e.g. `"reduced-default"`) |
| 6 | compression name | string | `NONE`, `GZIP`, or `LZ4FRAMED` |
| 7 | compression level | unsigned varint | ordinal into `FAST, MEDIUM, HIGH_COMPRESSION, MAX_COMPRESSION` |

Only the config **name** lives in the header. The full set of reduction flags is
serialized in the compressed main stream (as a reflective struct of
`Configuration`) and reconstructed on read, so it is never duplicated here.

## Main stream

After the header, the (optionally compressed) main stream contains, in order:

1. Type specifications (each introduced by its specified-type id).
2. The `Configuration` struct and the `Universe`.
3. Event instances, each prefixed by its type id.

The reader stops when it hits the `FOOTER_TYPE_ID` (7) sentinel — see below — so
it never mis-parses footer bytes as event data even when compression is `NONE`.

## Footer

`CJFRFooter.writeTo` produces the footer record; `CondensedOutputStream.writeFooter`
frames it into the raw stream. On disk, starting at `footerStart`:

```
[ 1 byte: FOOTER_TYPE_ID sentinel (7) ]
[ zlib-compressed footer blob         ]
[ 4 bytes: little-endian uint32 = blob length ]
```

The compressed blob, once inflated, is:

| # | Field | Type | Notes |
| --- | --- | --- | --- |
| 1 | footer type id | unsigned varint | `7` again, inside the blob |
| 2 | magic | 4 bytes | `C J F R` |
| 3 | version | unsigned varint | footer version; currently `1` |
| 4 | flags | 1 byte | bit0 = has GcStats, bit1 = has CpuStats, bit2 = has AllocStats |
| 5 | total events | unsigned varint | |
| 6 | start time (µs) | long8 | |
| 7 | duration (µs) | signed varint | |
| 8 | **main-stream CRC32** | long8 | CRC32 over `[0, footerStart)`; `0` if not recorded |
| 9 | event counts | varint count + (string, varint)* | per-event-type totals |
| 10 | GcStats / CpuStats / AllocStats | present per flags | summary statistics |

The reader locates the footer in O(1): read the last 4 bytes for the blob length,
seek back `4 + length` to find the blob, inflate, and parse. This is how
`cjfr summary` answers queries without touching the main stream.

## Integrity: whole-file CRC32

Field 8 above is a CRC32 computed over every byte from offset 0 up to
`footerStart` — the start header plus the compressed main stream, i.e. everything
except the footer record itself. It is captured at write time immediately after
the compressor is closed and flushed, so it covers exactly the finalized on-disk
bytes.

`CJFRFooterReader.verify(Path)` re-streams `[0, footerStart)` through a fresh
CRC32 and compares. On mismatch it throws `IntegrityCheckException`. This catches
silent bit-rot in the middle of the compressed stream, which truncation guards
alone would miss.

Verification is **file-only**: it needs to re-read the raw bytes, which is not
possible for a non-seekable `InputStream` (e.g. a `.cjfr` entry inside a ZIP). In
those cases verification is skipped and logged. `cjfr inflate` and `cjfr summary`
run verification before opening the reader for direct regular-file inputs; pass
`--ignore-integrity` to bypass it.

A stored CRC of `0` means the footer predates the CRC feature (or was written by a
tool that did not compute it); verification is then a no-op.

## Error handling on read

The reader fails loudly on incompatible or corrupt input:

| Condition | Exception |
| --- | --- |
| Header version greater than this build supports | `UnsupportedFormatVersionException` |
| Unknown compression name in the header | `UnknownCompressionException` |
| Whole-file CRC32 mismatch (file input) | `IntegrityCheckException` |

Truncation (a short read at the end of the stream) still degrades gracefully:
the reader returns the events it managed to parse rather than throwing, because a
cut-off recording is a common and recoverable situation, distinct from
mid-stream corruption.
