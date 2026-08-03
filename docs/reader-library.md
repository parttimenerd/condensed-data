---
title: Reader Library
---

# Reader Library

The `condensed-data-reader` artifact lets any Java application read `.cjfr` files
without taking the full CLI, agent, or JMC classes as dependencies.

**Coordinates:** `me.bechberger:condensed-data-reader:VERSION`

**Size:** ~500 KB + `lz4-java` (~700 KB). No JMC, no femtocli, no agent code.

**Requires:** Java 17+

---

## Maven dependency

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>condensed-data-reader</artifactId>
    <version>0.1.1</version>
</dependency>
```

---

## Quick start

### Read a single file

```java
import me.bechberger.cjfr.*;
import java.nio.file.Path;

try (CJFRFile f = CJFRFile.open(Path.of("recording.cjfr"))) {
    CJFREvent e;
    while ((e = f.readEvent()) != null) {
        System.out.println(e.getEventType().getName() + " @ " + e.getStartTime());
    }
}
```

### Read multiple files in time order

```java
import me.bechberger.cjfr.*;
import java.nio.file.*;
import java.util.List;

var paths = List.of(
    Path.of("app_0.cjfr"),
    Path.of("app_1.cjfr"),
    Path.of("app_2.cjfr"));

try (CJFRFiles files = CJFRFiles.open(paths)) {
    CJFREvent e;
    while ((e = files.readEvent()) != null) {
        if (e.getEventType().getName().equals("jdk.GarbageCollection")) {
            long pause = e.getLong("longestPause");
            System.out.printf("GC id=%d pause=%d ns%n", e.getInt("gcId"), pause);
        }
    }
}
```

---

## API reference

### `CJFRFile`

Single-file reader. Implements `AutoCloseable`.

| Method | Returns | Notes |
|---|---|---|
| `CJFRFile.open(Path)` | `CJFRFile` | Opens a `.cjfr` file |
| `CJFRFile.open(Path, Options)` | `CJFRFile` | Opens with custom options |
| `CJFRFile.open(InputStream)` | `CJFRFile` | Opens from stream (no footer access) |
| `readEvent()` | `CJFREvent` or `null` | Next event; `null` at EOF |
| `readAllEvents()` | `List<CJFREvent>` | Reads all remaining events |
| `hasMoreEvents()` | `boolean` | Peeks ahead |
| `getStartTime()` | `Instant` or `null` | From footer or stream header |
| `getEndTime()` | `Instant` or `null` | From footer or stream header |
| `getDuration()` | `Duration` | Total recording duration |
| `getFormatVersion()` | `int` | Footer format version; `-1` if absent |
| `getGeneratorConfiguration()` | `String` | `"default"`, `"lossless"`, etc. |
| `getEventTypes()` | `List<CJFREventType>` | Populated after reading |
| `isTruncated()` | `boolean` | `true` for in-progress recordings |
| `getFooter()` | `CJFRFooter` or `null` | Raw footer object |

### `CJFRFiles`

Multi-file reader. Merges files in time order. Implements `AutoCloseable`.

| Method | Returns | Notes |
|---|---|---|
| `CJFRFiles.open(List<Path>)` | `CJFRFiles` | Opens and merges |
| `CJFRFiles.open(List<Path>, Options)` | `CJFRFiles` | Opens with custom options |
| `readEvent()` | `CJFREvent` or `null` | Next event in time order |
| `readAllEvents()` | `List<CJFREvent>` | All remaining events |
| `getStartTime()` | `Instant` | Earliest start |
| `getEndTime()` | `Instant` | Latest end |
| `getDuration()` | `Duration` | Total span |

### `CJFREvent`

A single event. Wraps the lazy-loading struct.

| Method | Notes |
|---|---|
| `getEventType()` | Returns `CJFREventType` |
| `getStartTime()` | `Instant` or `null` |
| `getDuration()` | `Duration` or `null` |
| `getString(fieldName)` | Field as string |
| `getLong(fieldName)` | Field as `long` |
| `getInt(fieldName)` | Field as `int` |
| `getDouble(fieldName)` | Field as `double` |
| `getBoolean(fieldName)` | Field as `boolean` |
| `getInstant(fieldName)` | Timestamp field as `Instant` |
| `getDuration(fieldName)` | Duration field as `Duration` |
| `getStruct(fieldName)` | Nested struct as `CJFREvent` |
| `getList(fieldName)` | Array field as `List<?>` |
| `getValue(fieldName)` | Raw value; useful when type is unknown |
| `hasField(fieldName)` | Check field existence |
| `getFieldNames()` | All field names on this type |
| `getRawStruct()` | Low-level `ReadStruct` for advanced access |

### `CJFREventType`

Event type metadata.

| Method | Notes |
|---|---|
| `getName()` | Fully-qualified name, e.g. `jdk.GarbageCollection` |
| `getLabel()` | Human-readable label from `@Label`, e.g. `Garbage Collection` |
| `isExperimental()` | `true` if `@jdk.jfr.Experimental` is present |
| `getFieldNames()` | Declared field names |
| `getFields()` | `List<CJFRFieldType>` with name, label, type |

### `Options`

Immutable configuration. All methods return a new instance.

| Builder method | Notes |
|---|---|
| `Options.defaults()` | Default options |
| `.withReconstitution(boolean)` | Expand combined events (default: `true`) |
| `.withEventFilter(Predicate<String>)` | Filter by event type name |
| `.withEventTypes(Set<String>)` | Convenience for a fixed set |
| `.skipIntegrityCheck()` | Skip CRC32 check (useful for in-progress files) |

---

## Field names

Field names match the JFR specification names. Use `event.getFieldNames()` to enumerate
them at runtime, or cross-reference with `jfr print --json` output or the JDK
[Event Descriptions](https://openjdk.org/jeps/349).

Common fields:

| Field | Type | Appears on |
|---|---|---|
| `startTime` | `Instant` | Most events |
| `duration` | `Duration` | Duration events (GC, compilation, …) |
| `gcId` | `int` | GC events |
| `cause` | `String` | `jdk.GarbageCollection` |
| `longestPause` | `long` (ns) | `jdk.GarbageCollection` (G1/CMS) |
| `sumOfPauses` | `long` (ns) | `jdk.GarbageCollection` (G1/CMS) |
| `heapUsed` | `long` (bytes) | `jdk.GCHeapSummary` |
| `heapSpace.reservedSize` | Nested | `jdk.GCHeapSummary` |
| `stackTrace` | Nested | Sampling events |
| `sampledThread` | Nested | `jdk.ExecutionSample` |

!!! note "G1GC vs ZGC/Shenandoah"
    `longestPause` and `sumOfPauses` are G1GC-specific fields.
    For ZGC and Shenandoah, use `duration` on `jdk.GarbageCollection` instead.

---

## Examples

### Count GC events by cause

```java
try (CJFRFile f = CJFRFile.open(path)) {
    var byCause = new java.util.HashMap<String, Integer>();
    CJFREvent e;
    while ((e = f.readEvent()) != null) {
        if (e.getEventType().getName().equals("jdk.GarbageCollection")) {
            String cause = e.getString("cause");
            byCause.merge(cause, 1, Integer::sum);
        }
    }
    byCause.forEach((cause, count) ->
        System.out.printf("%-30s %d%n", cause, count));
}
```

### Filter to a specific event type

```java
Options opts = Options.defaults().withEventTypes(Set.of("jdk.GarbageCollection"));
try (CJFRFile f = CJFRFile.open(path, opts)) {
    f.readAllEvents().forEach(e ->
        System.out.println("GC #" + e.getInt("gcId") + " cause=" + e.getString("cause")));
}
```

### Access nested fields (stack trace)

```java
CJFREvent exec = ...; // jdk.ExecutionSample
CJFREvent stackTrace = exec.getStruct("stackTrace");
if (stackTrace != null) {
    List<?> frames = stackTrace.getList("frames");
    if (frames != null) {
        // Each frame is a CJFREvent with method, lineNumber, etc.
        frames.forEach(frame -> {
            if (frame instanceof CJFREvent f) {
                System.out.println(f.getStruct("method"));
            }
        });
    }
}
```

### Read recording metadata without iterating events

```java
try (CJFRFile f = CJFRFile.open(path)) {
    // Footer provides instant metadata (no stream reads needed)
    var footer = f.getFooter();
    if (footer != null) {
        System.out.println("Duration: " + f.getDuration());
        System.out.println("Total events: " + footer.totalEvents());
        footer.eventCounts().forEach((type, count) ->
            System.out.printf("  %-40s %d%n", type, count));
    }
}
```

---

## Format versions and compatibility

The reader handles all `.cjfr` format versions automatically.
Use `CJFRFile.getFormatVersion()` to inspect the footer version (1 or 2;
`-1` for files without a footer). Older files without footers still stream correctly —
metadata like start time is read from the stream header.

---

## Thread safety

`CJFRFile` and `CJFRFiles` are **not thread-safe**. Create one instance per thread or
use external synchronisation.
