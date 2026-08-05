# GC-Log Tag Research Notes

Generated: 2026-08-05  
Purpose: Inventory of all `-Xlog:gc*` tag combinations to inform the gc-log.jfc preset design.

---

## Sources

1. **`logTag.hpp` (canonical tag registry)** — `src/hotspot/share/logging/logTag.hpp` in OpenJDK jdk25u
   (`/home/i560383/code/jdk25u/` on thinkstation, also verified against
   `https://raw.githubusercontent.com/openjdk/jdk/master/src/hotspot/share/logging/logTag.hpp`)
2. **Live JVM on thinkstation** — `java -Xlog:help` (OpenJDK 21.0.11) emits the definitive
   "Available log tags" list at runtime.
3. **Source grep of jdk25u** — `grep -rh 'log_(info|debug|trace|…)(gc,…)'` over
   `src/hotspot/share/gc/{g1,z,shenandoah,parallel,serial,shared}/`
4. **`LogTarget(…, gc, …)` grep** — same tree, covers conditional-logging sites.
5. **`GCTraceTime(…, gc, …)` grep** — covers phase-timer sites.

---

## Tag Inventory

### All Tags Registered in logTag.hpp That Are Relevant to GC

These are the primitive tokens from `LOG_TAG_LIST` that appear in gc-prefixed log call
combinations (not the full 220-tag list, just those observed together with `gc`):

```
age          alloc        barrier      blocks       bot
breakpoint   cause        classhisto   compaction   coops
cpu          cset         director     ergo         exit
free         freelist     heap         humongous    ihop
init         liveness     load         marking      metaspace
mmu          nmethod      numa         oops         page
periodic     phases       plab         promotion    ptrqueue
ref          refine       region       reloc        remset
start        stats        stringdedup  survivor     sweep
task         thread       tlab         tracking     verify
```

### Complete List of Observed `-Xlog:gc+<tags>` Combinations

Sorted, deduplicated, derived from source grep + LogTarget + GCTraceTime on jdk25u:

```
gc
gc+age
gc+alloc
gc+alloc+region
gc+alloc+stats
gc+barrier
gc+bot
gc+breakpoint
gc+classhisto
gc+cpu
gc+cset
gc+director
gc+ergo
gc+ergo+cset
gc+ergo+heap
gc+ergo+ihop
gc+ergo+refine
gc+exit
gc+free
gc+freelist
gc+heap
gc+heap+exit
gc+heap+numa
gc+heap+region
gc+heap+verify
gc+humongous
gc+ihop
gc+init
gc+liveness
gc+load
gc+marking
gc+metaspace
gc+mmu
gc+nmethod
gc+nmethod+barrier
gc+nmethod+oops
gc+page
gc+periodic
gc+phases
gc+phases+ref
gc+phases+start
gc+phases+task
gc+plab
gc+promotion
gc+ptrqueue
gc+ref
gc+refine
gc+refine+stats
gc+region
gc+reloc
gc+remset
gc+remset+tracking
gc+start
gc+stats
gc+stringdedup
gc+task
gc+task+start
gc+task+stats
gc+thread
gc+tlab
gc+verify
```

**Note:** The `-Xlog` option uses `+` to combine tags (AND-match), e.g. `-Xlog:gc+heap=debug`.
The `gc*` wildcard matches all tags whose set *contains* `gc`, equivalent to listing every
combination above.

---

## GC-Implementation-Specific Tags

### G1 GC only

| Tag combination | What it logs |
|---|---|
| `gc+alloc+region` | Per-region allocation decisions |
| `gc+alloc+stats` | Allocation statistics |
| `gc+bot` | Block offset table operations |
| `gc+breakpoint` | Collection breakpoints |
| `gc+cset` | Collection set selection summary (also LogTarget) |
| `gc+ergo+cset` | Collection set ergonomic decisions |
| `gc+ergo+ihop` | Initiating Heap Occupancy Percentage decisions |
| `gc+ergo+refine` | Refinement goal adjustments |
| `gc+humongous` | Humongous object allocation/reclaim |
| `gc+ihop` | IHOP threshold reporting |
| `gc+liveness` | Region liveness during concurrent marking |
| `gc+mmu` | Max Mutator Utilization tracking |
| `gc+periodic` | Periodic GC timing |
| `gc+phases+ref` | Reference processing within phases |
| `gc+phases+task` | Parallel task detail within phases |
| `gc+plab` | Promotion Local Allocation Buffer |
| `gc+refine` | Card refinement thread activity |
| `gc+refine+stats` | Refinement statistics |
| `gc+remset+tracking` | Remembered set tracking updates |
| `gc+task+start` | Task start events |

### ZGC only

| Tag combination | What it logs |
|---|---|
| `gc+director` | ZGC director heuristic decisions |
| `gc+load` | ZGC load barrier activity |
| `gc+nmethod+barrier` | NMethod load-barrier installation |
| `gc+nmethod+oops` | NMethod oop iteration |
| `gc+page` | ZGC page allocation/free |
| `gc+phases+start` | Phase start events |
| `gc+reloc` | Relocation (ZGC compaction) detail |
| `gc+stats` | ZGC statistics summary |
| `gc+task+stats` | Per-task statistics |

### Shenandoah only

| Tag combination | What it logs |
|---|---|
| `gc+thread` | GC thread management |
| `gc+ergo` | Heuristic decisions (also used in G1/Parallel) |

### Parallel GC / Serial GC

| Tag combination | What it logs |
|---|---|
| `gc+bot` | Block offset table (also G1) |
| `gc+promotion` | Object promotion decisions |

### All collectors (shared)

```
gc          gc+age       gc+alloc      gc+barrier    gc+cpu
gc+ergo     gc+exit      gc+free       gc+heap       gc+init
gc+marking  gc+metaspace gc+phases     gc+ref        gc+refine
gc+region   gc+remset    gc+start      gc+task       gc+tlab
gc+verify
```

---

## Notes for JFR Event Mapping (Task 2)

Preliminary mapping sketch (not yet validated against C++ JFR fire sites):

| `-Xlog` tag combination | Likely JFR event(s) |
|---|---|
| `gc` | `jdk.GarbageCollection`, `jdk.YoungGarbageCollection`, `jdk.OldGarbageCollection`, `jdk.G1GarbageCollection`, `jdk.SystemGC` |
| `gc+start` | Start of GC cycle — part of `jdk.GarbageCollection` startTime |
| `gc+heap` | `jdk.GCHeapSummary`, `jdk.GCHeapConfiguration`, `jdk.GCHeapMemoryUsage` |
| `gc+heap+exit` | `jdk.GCHeapSummary` at JVM exit |
| `gc+phases` | `jdk.GCPhasePause`, `jdk.GCPhasePauseLevel1`–4, `jdk.GCPhaseConcurrent`, `jdk.GCPhaseConcurrentLevel1`–2, `jdk.GCPhaseParallel` |
| `gc+phases+ref` | `jdk.GCReferenceStatistics` |
| `gc+ref` | `jdk.GCReferenceStatistics` |
| `gc+tlab` | `jdk.GCTLABConfiguration`, `jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB` |
| `gc+age` | `jdk.TenuringDistribution` |
| `gc+cpu` | `jdk.GCCPUTime` |
| `gc+metaspace` | `jdk.MetaspaceSummary`, `jdk.MetaspaceGCThreshold` |
| `gc+ergo+ihop` | IHOP logging — no direct JFR event (gap) |
| `gc+ergo+cset` | `jdk.EvacuationInformation` (partial) |
| `gc+alloc` | `jdk.AllocationRequiringGC` |
| `gc+promotion` | `jdk.PromoteObjectInNewPLAB`, `jdk.PromoteObjectOutsidePLAB`, `jdk.PromotionFailed` |
| `gc+marking` | `jdk.GCPhaseConcurrent` (concurrent mark phases) |
| `gc+stringdedup` | `jdk.StringDeduplication` (if enabled) |
| `gc+init` | `jdk.GCConfiguration`, `jdk.GCHeapConfiguration` |
| `gc+mmu` | No direct JFR event (gap) — MMU is G1-specific |
| `gc+liveness` | No direct JFR event (gap) — region liveness is G1 debug |
| `gc+humongous` | No direct JFR event (gap) — humongous is G1 only |
| `gc+reloc` | No direct JFR event (ZGC relocation — gap) |
| `gc+director` | No direct JFR event (ZGC director — gap) |

Gaps (no JFR equivalent): `gc+mmu`, `gc+liveness`, `gc+humongous`, `gc+reloc`, `gc+director`,
`gc+ergo+ihop`, `gc+ergo+refine`, `gc+refine+stats`, `gc+remset+tracking`.

---

## Known Omissions / Open Questions

1. The `gc+cause` combination appears in documentation examples but was **not observed** in any
   log call in jdk25u source. Cause is embedded in the `jdk.GarbageCollection` event's `cause`
   field rather than a separate log line.
2. `gc+workgang` — mentioned in older JEP discussions but absent from jdk25u tag list.
3. `gc+sweep` — the tag `sweep` exists in logTag.hpp but was not observed in a `gc+sweep`
   combination in jdk25u (serial/CMS era, likely removed with CMS in JDK 15).
4. `gc+compaction` — similarly CMS-era; `compaction` tag exists but `gc+compaction` not observed
   in jdk25u shared/GC source.
5. `gc+load` — ZGC-specific load barrier; maps to no standard JFR event.
6. `gc+nmethod` / `gc+nmethod+barrier` / `gc+nmethod+oops` — ZGC-specific NMethod
   instrumentation; no JFR equivalent.

---

## JVM Version Context

- Source grepped: OpenJDK jdk25u (`/home/i560383/code/jdk25u/` on thinkstation)
- Runtime tested: OpenJDK 21.0.11 (on thinkstation) — tag list matches jdk25u
- GitHub cross-check: `https://github.com/openjdk/jdk` master branch (verified 2026-08-05)
- CMS GC was removed in JDK 15 — several older tag combinations (sweep, compaction in gc
  context) are vestigial or absent in modern source
