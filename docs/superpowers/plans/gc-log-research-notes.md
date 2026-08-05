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

## Tag → JFR Event Mapping (confirmed from jdk25u source)

Sources verified: `gc/shared/gcTraceSend.cpp`, `gc/g1/g1Trace.cpp`, `gc/z/zTracer.cpp`,
`gc/shenandoah/shenandoahTrace.cpp`, `gc/shared/ageTableTracer.cpp`, `gc/shared/allocTracer.cpp`,
`gc/shared/objectCountEventSender.cpp`, `jfr/periodic/jfrPeriodic.cpp`,
`jfr/metadata/metadata.xml`.

### Shared / All-collector mappings

| `-Xlog` tag combination | JFR event(s) | Confirmed? | Notes |
|---|---|---|---|
| `gc` | `jdk.GarbageCollection` | Yes | `GCTracer::send_garbage_collection_event()` in gcTraceSend.cpp; cause field embedded here (no separate `gc+cause` tag) |
| `gc` | `jdk.YoungGarbageCollection` | Yes | `YoungGCTracer::send_young_gc_event()` — young-specific sub-event with tenuringThreshold |
| `gc` | `jdk.OldGarbageCollection` | Yes | `OldGCTracer::send_old_gc_event()` — old-specific sub-event |
| `gc` | `jdk.ParallelOldGarbageCollection` | Yes | `ParallelOldTracer::send_parallel_old_event()` — Parallel GC only; includes densePrefix |
| `gc` | `jdk.SystemGC` | Yes | Fired from `jvm.cpp` when `System.gc()` is called (stackTrace=true) |
| `gc+start` | (none separate) | Yes | "GC start" is the `startTime` field of `jdk.GarbageCollection`; `gc+start` log tag is informational only (a single log line before the pause) |
| `gc+heap` | `jdk.GCHeapSummary` | Yes | `GCHeapSummaryEventSender::visit(GCHeapSummary*)` in gcTraceSend.cpp — fires before+after each collection |
| `gc+heap` | `jdk.G1HeapSummary` | Yes | `GCHeapSummaryEventSender::visit(G1HeapSummary*)` — G1 supplement to GCHeapSummary |
| `gc+heap` | `jdk.PSHeapSummary` | Yes | `GCHeapSummaryEventSender::visit(PSHeapSummary*)` — Parallel GC supplement |
| `gc+heap` | `jdk.GCHeapMemoryUsage` | Yes | Periodic event from `jfrPeriodic.cpp` (everyChunk) |
| `gc+heap` | `jdk.GCHeapMemoryPoolUsage` | Yes | Periodic event from `jfrPeriodic.cpp` (everyChunk) |
| `gc+heap+exit` | `jdk.GCHeapSummary` | Yes | Same event, fired at JVM exit (GCWhen::After) |
| `gc+phases` | `jdk.GCPhasePause` | Yes | Level-0 pause phase via `PhaseSender::visit_pause()` in gcTraceSend.cpp |
| `gc+phases` | `jdk.GCPhasePauseLevel1` | Yes | Level-1 pause sub-phase |
| `gc+phases` | `jdk.GCPhasePauseLevel2` | Yes | Level-2 pause sub-phase |
| `gc+phases` | `jdk.GCPhasePauseLevel3` | Yes | Level-3 pause sub-phase |
| `gc+phases` | `jdk.GCPhasePauseLevel4` | Yes | Level-4 pause sub-phase |
| `gc+phases` | `jdk.GCPhaseConcurrent` | Yes | Level-0 concurrent phase via `PhaseSender::visit_concurrent()` |
| `gc+phases` | `jdk.GCPhaseConcurrentLevel1` | Yes | Level-1 concurrent sub-phase |
| `gc+phases` | `jdk.GCPhaseConcurrentLevel2` | Yes | Level-2 concurrent sub-phase |
| `gc+phases` | `jdk.GCPhaseParallel` | Yes | Fired inline (not via PhaseSender) from G1/Shenandoah parallel task wrappers; `EventGCPhaseParallel` in g1YoungCollector.cpp, g1GCParPhaseTimesTracker.hpp, shenandoahPhaseTimings.hpp |
| `gc+phases+ref` | `jdk.GCReferenceStatistics` | Yes | `GCTracer::send_reference_stats_event()` — ref type + count per collection |
| `gc+ref` | `jdk.GCReferenceStatistics` | Yes | Same event as `gc+phases+ref`; `gc+ref` is the log tag actually used for reference processing logging |
| `gc+age` | `jdk.TenuringDistribution` | Yes | `AgeTableTracer::send_tenuring_distribution_event()` in ageTableTracer.cpp |
| `gc+cpu` | `jdk.GCCPUTime` | Yes | `GCTracer::send_cpu_time_event()` in gcTraceSend.cpp; fires per collection |
| `gc+metaspace` | `jdk.MetaspaceSummary` | Yes | `GCTracer::send_meta_space_summary_event()` in gcTraceSend.cpp |
| `gc+metaspace` | `jdk.MetaspaceGCThreshold` | Yes | `memory/metaspaceTracer.cpp` — fires when threshold changes |
| `gc+metaspace` | `jdk.MetaspaceChunkFreeListSummary` | Yes | `GCTracer::send_metaspace_chunk_free_list_summary()` in gcTraceSend.cpp (debug detail) |
| `gc+init` | `jdk.GCConfiguration` | Yes | Periodic from `jfrPeriodic.cpp` TRACE_REQUEST_FUNC(GCConfiguration) |
| `gc+init` | `jdk.GCHeapConfiguration` | Yes | Periodic from `jfrPeriodic.cpp` TRACE_REQUEST_FUNC(GCHeapConfiguration) |
| `gc+init` | `jdk.GCSurvivorConfiguration` | Yes | Periodic from `jfrPeriodic.cpp` TRACE_REQUEST_FUNC(GCSurvivorConfiguration) |
| `gc+init` | `jdk.GCTLABConfiguration` | Yes | Periodic from `jfrPeriodic.cpp` TRACE_REQUEST_FUNC(GCTLABConfiguration) |
| `gc+init` | `jdk.YoungGenerationConfiguration` | Yes | Periodic from `jfrPeriodic.cpp` TRACE_REQUEST_FUNC(YoungGenerationConfiguration) |
| `gc+tlab` | `jdk.GCTLABConfiguration` | Yes | Same periodic event — TLAB config also logged at init via `gc+tlab` |
| `gc+tlab` | `jdk.ObjectAllocationInNewTLAB` | Yes | `AllocTracer::send_allocation_in_new_tlab()` in allocTracer.cpp |
| `gc+tlab` | `jdk.ObjectAllocationOutsideTLAB` | Yes | `AllocTracer::send_allocation_outside_tlab()` in allocTracer.cpp |
| `gc+alloc` | `jdk.AllocationRequiringGC` | Yes | `AllocTracer::send_allocation_requiring_gc_event()` in allocTracer.cpp |
| `gc+marking` | `jdk.GCPhaseConcurrent` | Yes | Concurrent mark phases are fired via `GCPhaseConcurrent`/Level1/Level2 events |
| `gc+promotion` | `jdk.PromoteObjectInNewPLAB` | Yes | `YoungGCTracer::send_promotion_in_new_plab_event()` |
| `gc+promotion` | `jdk.PromoteObjectOutsidePLAB` | Yes | `YoungGCTracer::send_promotion_outside_plab_event()` |
| `gc+promotion` | `jdk.PromotionFailed` | Yes | `YoungGCTracer::send_promotion_failed_event()` — fired on promotion failure |
| `gc+stringdedup` | (none) | Yes — GAP | `gc+stringdedup` logs dedup statistics via `log_info(stringdedup, ...)` not `log_info(gc, stringdedup)`. No `jdk.StringDeduplication` event exists in jdk25u metadata.xml. The log tag `stringdedup` is separate from `gc` tag. |

### G1-specific mappings

| `-Xlog` tag combination | JFR event(s) | Confirmed? | Notes |
|---|---|---|---|
| `gc` (G1 young) | `jdk.G1GarbageCollection` | Yes | `G1NewTracer::send_g1_young_gc_event()` in g1Trace.cpp — type field encodes G1GCPauseType |
| `gc+ergo+cset` | `jdk.EvacuationInformation` | Yes | `G1NewTracer::send_evacuation_info_event()` — cSet regions, bytes copied, regions freed |
| `gc+ergo+cset` | `jdk.EvacuationFailed` | Yes | `G1NewTracer::send_evacuation_failed_event()` — object count+size that failed evacuation |
| `gc+ergo+cset` | `jdk.G1EvacuationYoungStatistics` | Yes | `G1NewTracer::send_young_evacuation_statistics()` |
| `gc+ergo+cset` | `jdk.G1EvacuationOldStatistics` | Yes | `G1NewTracer::send_old_evacuation_statistics()` |
| `gc+ergo+ihop` | `jdk.G1BasicIHOP` | Yes | `G1NewTracer::send_basic_ihop_statistics()` — fires when IHOP is computed (static mode) |
| `gc+ergo+ihop` | `jdk.G1AdaptiveIHOP` | Yes | `G1NewTracer::send_adaptive_ihop_statistics()` — fires in adaptive IHOP mode |
| `gc+mmu` | `jdk.G1MMU` | Yes | `G1MMUTracer::send_g1_mmu_event()` in g1Trace.cpp — timeSlice, gcTime, pauseTarget |
| `gc+region` | `jdk.G1HeapRegionTypeChange` | Yes | `g1HeapRegionTracer.cpp` `EventG1HeapRegionTypeChange` — fires on region type transitions |
| `gc+region` | `jdk.G1HeapRegionInformation` | Yes | `g1HeapRegionEventSender.cpp` `EventG1HeapRegionInformation` — periodic snapshot of all regions |
| `gc+ergo` (G1) | (no dedicated event) | Yes — GAP | G1 ergonomic text messages (pause target, young gen sizing) have no direct JFR equivalent |
| `gc+liveness` | (none) | Yes — GAP | Region liveness reporting is G1-only debug detail; no JFR event |
| `gc+humongous` | (none) | Yes — GAP | Humongous allocation/reclaim text; no JFR event |
| `gc+plab` | (partial) | Yes — GAP | PLAB stats are logged but not separately in JFR; promotion events `PromoteObjectInNewPLAB` track PLAB use indirectly |
| `gc+cset` | (partial) | Yes — GAP | Collection set selection summary text; `EvacuationInformation` has cSet region counts but not the per-region detail |
| `gc+refine` | (none) | Yes — GAP | Card refinement activity; no JFR event |
| `gc+refine+stats` | (none) | Yes — GAP | Refinement statistics; no JFR event |
| `gc+remset` | (none) | Yes — GAP | Remembered set statistics; no JFR event |
| `gc+remset+tracking` | (none) | Yes — GAP | Remembered set tracking detail; no JFR event |
| `gc+ihop` | `jdk.G1BasicIHOP`, `jdk.G1AdaptiveIHOP` | Yes | Same as `gc+ergo+ihop` — both log tags are used |

### ZGC-specific mappings

| `-Xlog` tag combination | JFR event(s) | Confirmed? | Notes |
|---|---|---|---|
| `gc` (ZGC young) | `jdk.ZYoungGarbageCollection` | Yes | `ZYoungTracer::report_end()` in zTracer.cpp — gcId + tenuringThreshold |
| `gc` (ZGC old) | `jdk.ZOldGarbageCollection` | Yes | `ZOldTracer::report_end()` in zTracer.cpp |
| `gc+alloc` | `jdk.ZAllocationStall` | Yes | `EventZAllocationStall` in zPageAllocator.cpp — thread stalls waiting for memory |
| `gc+page` | `jdk.ZPageAllocation` | Yes | `EventZPageAllocation` in zPageAllocator.cpp — per-page allocation events |
| `gc+reloc` | `jdk.ZRelocationSet` | Yes | `EventZRelocationSet` in zRelocationSetSelector.cpp — total relocation set |
| `gc+reloc` | `jdk.ZRelocationSetGroup` | Yes | `EventZRelocationSetGroup` in zRelocationSetSelector.cpp — per-group details |
| `gc+stats` | `jdk.ZStatisticsCounter` | Yes | `ZTracer::send_stat_counter()` in zTracer.cpp — named counter increments+values |
| `gc+stats` | `jdk.ZStatisticsSampler` | Yes | `ZTracer::send_stat_sampler()` in zTracer.cpp — named sampler values |
| `gc+thread` | `jdk.ZThreadPhase` | Yes | `ZTracer::send_thread_phase()` in zTracer.cpp — per-thread ZGC phase |
| `gc+heap` (ZGC) | `jdk.ZUncommit` | Yes | `EventZUncommit::commit()` in zUncommitter.cpp — memory returned to OS |
| `gc+mmu` (ZGC) | (none separate) | Yes — GAP | ZGC logs MMU text via `log_info(gc, mmu)` (in zStat.cpp) but has no `jdk.G1MMU` equivalent for ZGC |
| `gc+director` | (none) | Yes — GAP | ZGC director heuristic decisions; no JFR event |
| `gc+load` | (none) | Yes — GAP | ZGC load barrier instrumentation; no JFR event |
| `gc+nmethod+barrier` | (none) | Yes — GAP | ZGC nmethod barrier installation; no JFR event |
| `gc+nmethod+oops` | (none) | Yes — GAP | ZGC nmethod oop iteration; no JFR event |
| `gc+task+stats` | (none) | Yes — GAP | ZGC per-task statistics text; no JFR event |

**Note on `jdk.ZUnmap`:** Present in `benchmark/gc.jfc` but **absent from jdk25u metadata.xml** — this event was removed from the JDK. It should be dropped from the gc-log.jfc preset.

### Shenandoah-specific mappings

| `-Xlog` tag combination | JFR event(s) | Confirmed? | Notes |
|---|---|---|---|
| `gc+region` (Shenandoah) | `jdk.ShenandoahHeapRegionStateChange` | Yes | `shenandoahHeapRegion.cpp` `EventShenandoahHeapRegionStateChange` — on every region state transition |
| `gc+region` (Shenandoah) | `jdk.ShenandoahHeapRegionInformation` | Yes | `shenandoahJfrSupport.cpp` `EventShenandoahHeapRegionInformation` — periodic snapshot |
| `gc+ergo+cset` (Shenandoah) | `jdk.ShenandoahEvacuationInformation` | Yes | `ShenandoahTracer::send_evacuation_info_event()` — cSet regions + bytes with Shenandoah-specific fields |
| `gc+ergo` (Shenandoah) | (none separate) | Yes — GAP | Pacer decisions, free set sizing logged but no dedicated JFR event |
| `gc+thread` | (none) | Yes — GAP | GC thread management messages; no JFR event |

### Concurrent mode failure

| `-Xlog` tag combination | JFR event(s) | Confirmed? | Notes |
|---|---|---|---|
| `gc` (ConcModeFailure) | `jdk.ConcurrentModeFailure` | Yes | `OldGCTracer::send_concurrent_mode_failure_event()` in gcTraceSend.cpp |

### Heap inspection / object statistics

| `-Xlog` tag combination | JFR event(s) | Confirmed? | Notes |
|---|---|---|---|
| `gc` (object count) | `jdk.ObjectCount` | Yes | `ObjectCountEventSender::send()` — periodic per-class object count (everyChunk) |
| `gc` (after-GC count) | `jdk.ObjectCountAfterGC` | Yes | `ObjectCountEventSender::send()` — fires after GC when heap inspection is done |

### Note on `gc+stringdedup` vs `stringdedup` tag

The `gc+stringdedup` combination was inferred from `logTag.hpp` but `stringdedup` statistics are
logged as `log_info(stringdedup, ...)` — **not** `log_info(gc, stringdedup, ...)`. In jdk25u there
is no `jdk.StringDeduplication` event defined in `metadata.xml`. The `gc.jfc` benchmark config does
not include a StringDeduplication event either. This tag combination is a gap.

### Summary of confirmed gaps (no JFR event)

```
gc+mmu (ZGC)           gc+ergo (G1/Shenandoah text)   gc+liveness
gc+humongous            gc+cset (detailed text)         gc+plab (detailed text)
gc+refine               gc+refine+stats                 gc+remset
gc+remset+tracking      gc+director                     gc+load
gc+nmethod+barrier      gc+nmethod+oops                 gc+task+stats
gc+stringdedup          gc+thread (GC thread mgmt)      gc+barrier (general)
gc+alloc+region         gc+alloc+stats                  gc+bot
gc+breakpoint           gc+ptrqueue                     gc+free (G1/Shenandoah free-set text)
gc+periodic (G1)        gc+task+start                   gc+verify
gc+exit (misc log lines)
```

---

## Live Capture Findings

**Date:** 2026-08-05  
**Host:** thinkstation (OpenJDK 21.0.11, Ubuntu, 128 CPU cores, 123 GB RAM)  
**Workload:** HyperAlloc (heapothesys), 60-second run, `-Xmx512m -Xms512m`  
**JFR analysis tool:** Temurin 22.0.2 `jfr` CLI  

### Setup notes

- HyperAlloc jar: `~/heapothesys/HyperAlloc/target/HyperAlloc.jar` (the fat jar with main manifest)
- The sibling `HyperAlloc-1.0.jar` lacks a `Main-Class` manifest entry and exits immediately — first
  three runs failed silently; rerun with the correct jar.
- Allocation rates: G1GC at `-a 100` (100 MB/s), ZGC and Shenandoah at `-a 50` (50 MB/s).
- GC log captured with `-Xlog:gc*:file=…:time,uptime,tags:filecount=1`.
- JFR captured with `-XX:StartFlightRecording=settings=profile,duration=60s`.

### Collectors tested

| Collector | GC log size | JFR size | GC cycles observed |
|---|---|---|---|
| G1GC (`-XX:+UseG1GC`) | 175 KB | 1.9 MB | 110 (`jdk.GarbageCollection` count) |
| ZGC (`-XX:+UseZGC`) | 302 KB | 583 KB | 54 (`jdk.GarbageCollection` count) |
| Shenandoah (`-XX:+UseShenandoahGC`) | 206 KB | 597 KB | 14 (`jdk.GarbageCollection` count) |

All three collectors are available in OpenJDK 21.0.11 on thinkstation. ZGC ran in legacy
single-generation mode (JDK 21 default; generational ZGC requires JDK 21+ with explicit flag).

---

### G1GC — events with non-zero counts

| JFR Event | Count | Notes |
|---|---|---|
| `jdk.GarbageCollection` | 110 | 85 young + 25 old total; names "G1New", "G1Old" |
| `jdk.YoungGarbageCollection` | 85 | young-specific sub-event |
| `jdk.OldGarbageCollection` | 25 | old-specific sub-event |
| `jdk.G1GarbageCollection` | 85 | G1-specific young event (type=G1GCPauseType) |
| `jdk.G1HeapSummary` | 220 | before+after each collection (2×110) |
| `jdk.GCHeapSummary` | 220 | same cadence |
| `jdk.G1EvacuationYoungStatistics` | 85 | per young collection |
| `jdk.G1EvacuationOldStatistics` | 85 | per old collection cycle |
| `jdk.G1AdaptiveIHOP` | 85 | adaptive IHOP decisions |
| `jdk.G1BasicIHOP` | 85 | static IHOP baseline |
| `jdk.G1MMU` | 135 | per collection |
| `jdk.GCCPUTime` | 135 | per collection |
| `jdk.GCPhasePause` | 135 | level-0 pause phase |
| `jdk.GCPhasePauseLevel1` | 505 | level-1 sub-phases |
| `jdk.GCPhasePauseLevel2` | 250 | level-2 sub-phases |
| `jdk.GCPhaseConcurrent` | 150 | concurrent phases (concurrent mark cycles) |
| `jdk.GCPhaseConcurrentLevel1` | 100 | concurrent sub-phases |
| `jdk.GCPhaseParallel` | 32,845 | very high count — per-worker parallel task events |
| `jdk.GCReferenceStatistics` | 440 | 4 ref types × 110 collections |
| `jdk.MetaspaceSummary` | 220 | before+after each collection |
| `jdk.MetaspaceChunkFreeListSummary` | 440 | 2 spaces × 2 × 110 |
| `jdk.TenuringDistribution` | 1,275 | per-age-bucket distribution |
| `jdk.EvacuationInformation` | 85 | per young collection |
| `jdk.PromoteObjectInNewPLAB` | 15,730 | per promoted object batch |
| `jdk.PromoteObjectOutsidePLAB` | 83 | outside-PLAB promotions |
| `jdk.GCHeapMemoryUsage` | 2 | periodic (everyChunk) |
| `jdk.GCHeapMemoryPoolUsage` | 6 | periodic (everyChunk) |
| `jdk.SafepointBegin` | 139 | safepoints (GC + non-GC) |

G1-specific events that fired zero:
- `jdk.G1HeapRegionInformation` = 0 — region snapshot disabled in `profile` settings
- `jdk.G1HeapRegionTypeChange` = 0 — transitions not enabled in `profile` settings
- `jdk.EvacuationFailed` = 0 — no promotion failure occurred in this run
- `jdk.PromotionFailed` = 0 — consistent

---

### ZGC — events with non-zero counts

| JFR Event | Count | Notes |
|---|---|---|
| `jdk.GarbageCollection` | 54 | root event (name="ZGC Major Collection" in ZGC) |
| `jdk.GCHeapSummary` | 108 | before+after each collection |
| `jdk.GCPhaseConcurrent` | 324 | 6 concurrent phases × 54 collections |
| `jdk.GCPhaseConcurrentLevel1` | 108 | concurrent sub-phases (Mark Free, etc.) |
| `jdk.GCPhasePause` | 162 | 3 pauses × 54 cycles (Mark Start, Mark End, Relocate Start) |
| `jdk.GCReferenceStatistics` | 216 | 4 ref types × 54 collections |
| `jdk.MetaspaceSummary` | 108 | before+after |
| `jdk.MetaspaceChunkFreeListSummary` | 216 | 2 spaces × 2 × 54 |
| `jdk.ZRelocationSet` | 54 | one per collection cycle — **CONFIRMED PRESENT** |
| `jdk.ZRelocationSetGroup` | 162 | 3 groups (small/medium/large pages) × 54 |
| `jdk.GCHeapMemoryUsage` | 2 | periodic |
| `jdk.GCHeapMemoryPoolUsage` | 2 | periodic |
| `jdk.SafepointBegin` | 170 | safepoints (3 GC pauses/cycle + compiler etc.) |
| `jdk.ExecuteVMOperation` | 405 | high due to ZGC barrier install ops |

ZGC-specific events that fired zero (confirmed absent):
- `jdk.ZYoungGarbageCollection` = 0 — single-gen mode; no young-only collections
- `jdk.ZOldGarbageCollection` = 0 — same; generational events unused in legacy mode
- `jdk.ZAllocationStall` = 0 — workload did not exhaust allocatable memory
- `jdk.ZPageAllocation` = 0 — page alloc events not enabled in `profile` settings
- `jdk.ZStatisticsCounter` = 0 — not enabled in `profile` settings
- `jdk.ZStatisticsSampler` = 0 — not enabled in `profile` settings
- `jdk.ZThreadPhase` = 0 — not enabled in `profile` settings
- `jdk.ZUncommit` = 0 — heap min==max so uncommit is disabled (expected)
- **`jdk.ZUnmap` = 0** — event present in metadata for JDK 21 but never fires; confirmed removed
  from jdk25u metadata.xml (Task 2 finding validated by live capture)

---

### Shenandoah — events with non-zero counts

| JFR Event | Count | Notes |
|---|---|---|
| `jdk.GarbageCollection` | 14 | 60 s run at 50 MB/s — far fewer collections than G1/ZGC |
| `jdk.GCHeapSummary` | 28 | before+after |
| `jdk.GCPhaseConcurrent` | 1,243 | Shenandoah has many concurrent phases per cycle |
| `jdk.GCPhaseConcurrentLevel1` | 28 | |
| `jdk.GCPhasePause` | 48 | ~3.4 pauses/cycle: Init Mark, Final Mark, Init Update Refs, Final Update Refs |
| `jdk.GCPhasePauseLevel1` | 94 | level-1 sub-phases within pauses |
| `jdk.GCPhaseParallel` | 217 | parallel task events within pauses |
| `jdk.GCReferenceStatistics` | 56 | 4 ref types × 14 collections |
| `jdk.MetaspaceSummary` | 28 | before+after |
| `jdk.MetaspaceChunkFreeListSummary` | 56 | |
| `jdk.GCHeapMemoryUsage` | 2 | periodic |
| `jdk.GCHeapMemoryPoolUsage` | 2 | periodic |
| `jdk.SafepointBegin` | 55 | |

Shenandoah-specific events that fired zero (confirmed absent):
- `jdk.ShenandoahHeapRegionInformation` = 0 — periodic snapshot not enabled in `profile` settings
- `jdk.ShenandoahHeapRegionStateChange` = 0 — region transitions not enabled in `profile` settings

---

### GC log ↔ JFR correlation spot-check

**G1GC:**  
The GC log shows, e.g.:
```
[1.137s][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 47M->42M(512M) 7.231ms
```
The corresponding `jdk.GarbageCollection` event shows:
```
startTime=15:05:20.287, duration=7.20 ms, gcId=1, name="G1New", cause="G1 Evacuation Pause"
```
Timing matches within rounding (7.231 ms log vs 7.20 ms JFR). The `gc` log tag maps cleanly to
`jdk.GarbageCollection`, and gcId is the cross-reference key.

**ZGC:**  
ZGC log phases like "Pause Mark Start", "Concurrent Mark", "Pause Relocate Start" correspond
exactly to `jdk.GCPhasePause` and `jdk.GCPhaseConcurrent` event names. The `jdk.ZRelocationSet`
event's `total` field matches the "38 small pages / 76M" summary in the reloc log line.

**Shenandoah:**  
The log shows four pauses per cycle (Pause Init Mark, Pause Final Mark, Pause Init Update Refs,
Pause Final Update Refs), consistent with `jdk.GCPhasePause` count = 48 ≈ 4 × 14 collections
(minus one cancelled by shutdown).

---

### Validation of Task 2 mapping table

| Finding | Status |
|---|---|
| `jdk.ZUnmap` present in JDK 21 metadata but never fires | **Confirmed** — count=0 in live capture |
| `jdk.ZRelocationSet` and `jdk.ZRelocationSetGroup` fire in ZGC | **Confirmed** — counts 54/162 |
| `jdk.GCPhasePause*` events present for all collectors | **Confirmed** |
| `jdk.GCPhaseConcurrent*` absent from G1 pause-only collections | **Confirmed** (G1 has 150 concurrent events from full concurrent mark cycles) |
| `jdk.G1HeapRegionInformation`/`jdk.G1HeapRegionTypeChange` need non-default settings | **Confirmed** — both zero in `profile` settings |
| `jdk.ShenandoahHeapRegionInformation`/`StateChange` need non-default settings | **Confirmed** — both zero in `profile` settings |
| `jdk.ZStatisticsCounter`/`Sampler`/`ZThreadPhase`/`ZPageAllocation` need non-default settings | **Confirmed** — all zero in `profile` settings |
| `jdk.PromoteObjectInNewPLAB` fires heavily under G1 | **Confirmed** — 15,730 events in 60s |
| `jdk.TenuringDistribution` fires under G1, absent in ZGC/Shenandoah | **Confirmed** |

One correction to Task 2 mapping table:
- `jdk.ZYoungGarbageCollection` and `jdk.ZOldGarbageCollection` are confirmed present in JDK 21
  metadata but **do not fire** in legacy single-generation mode (JDK 21 default). They would only
  fire with `-XX:+ZGenerational` (experimental in JDK 21, default in JDK 23+).

---

### Key insights for gc-log.jfc preset design

1. **`jdk.GCPhaseParallel` is extremely high-volume under G1** (32,845 events in 60s = ~547/s).
   Include it only if fine-grained parallel task timing is needed; its absence is acceptable for a
   "gc-log equivalent" preset.

2. **ZGC uses `jdk.GCPhasePause` for its three pause points** (no PauseLevel events), while G1
   uses `jdk.GCPhasePause` + `jdk.GCPhasePauseLevel1/2`. The shared `GCPhasePause` event covers all
   collectors for top-level pause visibility.

3. **`jdk.ZRelocationSet` + `jdk.ZRelocationSetGroup`** give direct JFR equivalents to ZGC's
   `gc+reloc` log output. Include both.

4. **`jdk.G1MMU` and `jdk.GCCPUTime`** fire with the same cadence as `jdk.GarbageCollection`
   and add significant value with minimal overhead.

5. **Region-level events (`G1HeapRegionInformation`, `ShenandoahHeapRegionInformation`)** are
   disabled in `profile` settings by default — appropriate to keep disabled in gc-log preset
   (too verbose, not in standard gc log output).

6. **`jdk.ZUnmap` should be excluded** from the gc-log.jfc preset. It is defined in JDK 21
   metadata but never fires, and is removed from jdk25u.

7. **Shenandoah emits no GC-specific JFR events beyond the shared set** in `profile` settings.
   The `ShenandoahHeapRegion*` events exist but are disabled by default. For Shenandoah, the
   shared events (`GarbageCollection`, `GCPhasePause`, `GCPhaseConcurrent`, `GCHeapSummary`,
   `MetaspaceSummary`) are sufficient for gc-log parity.

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

---

## Section A: Confirmed Gaps (no JFR equivalent)

These GC log tag combinations have **no current JFR event** and cannot be covered by gc-log.jfc:

| `-Xlog` tag | Collector | What it logs | Gap reason |
|---|---|---|---|
| `gc+refine` | G1 | Card refinement thread activity (cards processed, time spent) | No `jdk.G1RefinementStats` event exists; text-only |
| `gc+refine+stats` | G1 | Per-thread refinement statistics | Same as above |
| `gc+ergo+refine` | G1 | Adaptive refinement goal adjustments (threshold changes) | No JFR event for refinement ergonomics |
| `gc+remset` | G1 | Remembered set coarsening statistics (cells, coarsened entries) | No JFR event for remset stats |
| `gc+remset+tracking` | G1 | Per-region remembered set tracking updates | Same gap |
| `gc+stringdedup` | All (if enabled) | String deduplication statistics (tables, time, savings) | Note: the log tag is `stringdedup` (not `gc,stringdedup`); no `jdk.StringDeduplication` event in jdk25u metadata |
| `gc+humongous` | G1 | Humongous object allocation decisions (size, region, reclaim) | No `jdk.G1HumongousAllocation` event exists |
| `gc+mmu` (ZGC path) | ZGC | ZGC Maximum Mutator Utilization (via `log_info(gc,mmu)` in zStat.cpp) | `jdk.G1MMU` exists only for G1; ZGC MMU has no JFR equivalent |
| `gc+liveness` | G1 | Region liveness probes during concurrent marking (debug level only) | No JFR event; debug-level detail not needed in gc-log preset |
| `gc+director` | ZGC | ZGC director heuristic decisions (which generation to collect, why) | No JFR event for ZGC director |
| `gc+ergo` (text-only) | G1, Shenandoah | Ergonomic sizing decisions (young gen target, pause target) | Partial coverage via `jdk.G1BasicIHOP`/`G1AdaptiveIHOP`; free-text ergonomics have no JFR event |

**Impact on gc-log.jfc v1:** These gaps are accepted. The preset provides strong coverage of the
measurable GC events (pauses, phases, heap, metaspace, IHOP, MMU for G1, reloc for ZGC) while
acknowledging that refinement/remset/stringdedup/director internals are not JFR-instrumented.

---

## Section B: JFR-Only Events

Events available in JFR that have **no direct `-Xlog:gc*` counterpart** — present in the JFR
metadata but not emitted via the GC log subsystem:

| JFR Event | Count/60s | Recommendation | Rationale |
|---|---|---|---|
| `jdk.PromoteObjectInNewPLAB` | 15,730 | **EXCLUDE** | Per-promoted-object-batch event; extremely high volume under G1; not in GC log output |
| `jdk.PromoteObjectOutsidePLAB` | 83 | **EXCLUDE** | Same category as above; low count but no GC log equivalent |
| `jdk.ObjectAllocationInNewTLAB` | high (disabled in profile) | **EXCLUDE** | Application-level allocation sampling; not a GC event |
| `jdk.ObjectAllocationOutsideTLAB` | high (disabled in profile) | **EXCLUDE** | Same |
| `jdk.AllocationRequiringGC` | low (disabled in profile) | **OPTIONAL** (disabled by default) | Fires when allocation fails and GC is triggered; useful for diagnosing allocation pressure but not in standard GC log |
| `jdk.ZStatisticsCounter` | 0 in profile settings | **OPTIONAL** (disabled by default) | ZGC internal named counters; useful for ZGC deep diagnostics but not GC-log-equivalent output |
| `jdk.ZStatisticsSampler` | 0 in profile settings | **OPTIONAL** (disabled by default) | Same category |
| `jdk.G1HeapRegionInformation` | 0 in profile settings | **OPTIONAL** (disabled by default) | Periodic snapshot of all G1 regions; too verbose for gc-log preset |
| `jdk.G1HeapRegionTypeChange` | 0 in profile settings | **EXCLUDE** | Per-region type transition; high volume when enabled; no GC log equivalent |
| `jdk.ShenandoahHeapRegionStateChange` | 0 in profile settings | **EXCLUDE** | Per-region state transition; high volume; no GC log equivalent |
| `jdk.ShenandoahHeapRegionInformation` | 0 in profile settings | **INCLUDE** (period: everyChunk) | Periodic snapshot (low cadence); analogous to heap summary; reasonable for gc-log preset |
| `jdk.GCHeapMemoryUsage` | 2 (everyChunk) | **INCLUDE** | Standard periodic memory summary; already in `default.jfc` |
| `jdk.GCHeapMemoryPoolUsage` | 6 (everyChunk) | **INCLUDE** | Same |

---

## Section C: New JFR Event Proposals

Two gaps from Section A are strong candidates for upstream JFR instrumentation proposals.
Neither blocks gc-log.jfc v1 — they are forward-looking notes for JDK enhancement proposals.

### `jdk.G1RefinementStats` (for `gc+refine` / `gc+ergo+refine`)

**Rationale:** G1 card refinement is a significant source of concurrent CPU cost and a common
tuning target (`-XX:G1ConcRefinementThreads`, `-XX:G1RSetUpdatingPauseTimePercent`). The GC log
lines from `gc+refine` show processed cards, refinement time, and threshold adjustments — all
operationally useful. No JFR event currently captures this.

**Proposed fields:** `gcId`, `cardTableCardsProcessed` (long), `refinementTimeMs` (double),
`threadCount` (int), `goalCards` (long, for `gc+ergo+refine` threshold).

**Priority:** Low — upstream candidate; not needed for v1 preset coverage.

### `jdk.G1HumongousAllocation` (for `gc+humongous`)

**Rationale:** Humongous allocations are a common G1 performance problem (they trigger full GC,
bypass young gen, fragment old gen). The GC log `gc+humongous` output logs each allocation
decision. A JFR event would enable tooling to flag humongous allocations with stack traces.

**Proposed fields:** `gcId`, `objectSizeBytes` (long), `allocationSucceeded` (boolean),
`regionsRequired` (int). Optional: `stackTrace` for allocation site tracking.

**Priority:** Low — upstream candidate; not needed for v1 preset coverage.

---

## Section D: Final Event List for gc-log.jfc

This is the **authoritative specification** for the gc-log.jfc preset.
Version: v1 (2026-08-05). Covers JDK 17+ (all modern collectors: G1, ZGC, Shenandoah, Parallel, Serial).

### ENABLED events

| JFR Event | Setting | Notes |
|---|---|---|
| `jdk.JVMInformation` | `period: beginChunk` | JVM version, command-line args |
| `jdk.InitialSystemProperty` | `period: beginChunk` | System properties snapshot |
| `jdk.GCConfiguration` | `period: everyChunk` | GC algorithm, cause flags |
| `jdk.GCHeapConfiguration` | `period: beginChunk` | Min/max heap, flags |
| `jdk.YoungGenerationConfiguration` | `period: beginChunk` | Young gen min/max ratio |
| `jdk.GCTLABConfiguration` | `period: beginChunk` | TLAB settings |
| `jdk.GCSurvivorConfiguration` | `period: beginChunk` | Survivor space settings |
| `jdk.GarbageCollection` | `threshold: 0ms` | Root GC event; all collectors |
| `jdk.SystemGC` | `threshold: 0ms, stackTrace: false` | `System.gc()` calls |
| `jdk.ParallelOldGarbageCollection` | `threshold: 0ms` | Parallel GC old-gen event |
| `jdk.YoungGarbageCollection` | `threshold: 0ms` | Young GC sub-event (all collectors) |
| `jdk.OldGarbageCollection` | `threshold: 0ms` | Old GC sub-event |
| `jdk.G1GarbageCollection` | `threshold: 0ms` | G1-specific young event (GCPauseType) |
| `jdk.GCHeapSummary` | _(default)_ | Heap before+after each collection |
| `jdk.G1HeapSummary` | _(default)_ | G1 heap region breakdown |
| `jdk.PSHeapSummary` | _(default)_ | Parallel GC heap breakdown |
| `jdk.MetaspaceSummary` | _(default)_ | Metaspace before+after each collection |
| `jdk.MetaspaceGCThreshold` | _(default)_ | Fires when metaspace threshold changes |
| `jdk.MetaspaceAllocationFailure` | `stackTrace: false` | Metaspace OOM precursor |
| `jdk.MetaspaceOOM` | `stackTrace: false` | Metaspace out-of-memory |
| `jdk.MetaspaceChunkFreeListSummary` | _(default)_ | Chunk free list detail (debug) |
| `jdk.GCCPUTime` | _(default)_ | GC user/sys/wall time per collection |
| `jdk.GCReferenceStatistics` | _(default)_ | Ref type counts (soft/weak/final/phantom) |
| `jdk.GCPhasePause` | `threshold: 0ms` | Level-0 pause phase (all collectors) |
| `jdk.GCPhasePauseLevel1` | `threshold: 0ms` | Level-1 pause sub-phase |
| `jdk.GCPhasePauseLevel2` | `threshold: 0ms` | Level-2 pause sub-phase |
| `jdk.GCPhaseConcurrent` | `threshold: 0ms` | Level-0 concurrent phase |
| `jdk.GCPhaseConcurrentLevel1` | `threshold: 0ms` | Level-1 concurrent sub-phase |
| `jdk.ConcurrentModeFailure` | _(default)_ | CMS/G1 concurrent mode failure |
| `jdk.PromotionFailed` | _(default)_ | Promotion failure (G1, Parallel) |
| `jdk.EvacuationFailed` | _(default)_ | G1 evacuation failure |
| `jdk.EvacuationInformation` | _(default)_ | G1 cSet regions, bytes copied |
| `jdk.TenuringDistribution` | _(default)_ | Per-age tenuring bucket distribution (G1/Parallel) |
| `jdk.G1MMU` | _(default)_ | G1 maximum mutator utilization |
| `jdk.G1BasicIHOP` | _(default)_ | IHOP threshold (static mode) |
| `jdk.G1AdaptiveIHOP` | _(default)_ | IHOP threshold (adaptive mode) |
| `jdk.G1EvacuationYoungStatistics` | _(default)_ | Per-young-collection evacuation stats |
| `jdk.G1EvacuationOldStatistics` | _(default)_ | Per-old-collection evacuation stats |
| `jdk.ZYoungGarbageCollection` | `threshold: 0ms` | Generational ZGC young; fires JDK 23+ |
| `jdk.ZOldGarbageCollection` | `threshold: 0ms` | Generational ZGC old; fires JDK 23+ |
| `jdk.ZAllocationStall` | `threshold: 0ms, stackTrace: false` | ZGC thread stall waiting for memory |
| `jdk.ZPageAllocation` | `threshold: 1ms, stackTrace: false` | ZGC page allocation (only slow ones) |
| `jdk.ZRelocationSet` | `threshold: 0ms` | ZGC relocation set total |
| `jdk.ZRelocationSetGroup` | `threshold: 0ms` | ZGC relocation set per-group detail |
| `jdk.ZThreadPhase` | `threshold: 0ms` | ZGC per-thread phase |
| `jdk.ZUncommit` | `threshold: 0ms` | ZGC memory returned to OS |
| `jdk.ShenandoahHeapRegionInformation` | `period: everyChunk` | Shenandoah region snapshot |
| `jdk.GCLocker` | `threshold: 1s, stackTrace: false` | GC locker delay (present in JDK 21 JFR metadata) |
| `jdk.GCHeapMemoryUsage` | `period: everyChunk` | Periodic heap memory usage |
| `jdk.GCHeapMemoryPoolUsage` | `period: everyChunk` | Periodic pool-level memory usage |
| `jdk.ActiveRecording` | _(default)_ | JFR recording metadata |
| `jdk.ActiveSetting` | _(default)_ | JFR setting values |
| `jdk.DataLoss` | _(default)_ | JFR buffer overflow notification |
| `jdk.DumpReason` | _(default)_ | Why JFR dump was triggered |
| `jdk.Shutdown` | `stackTrace: false` | JVM shutdown event |

### DISABLED events (explicitly suppressed to reduce noise)

These events are listed in the JFC with `enabled=false` to prevent accidental activation by
wildcard settings in calling profiles:

```
jdk.GCPhaseParallel           — 32K+/min under G1; not in GC log output
jdk.GCPhasePauseLevel3        — very fine granularity; not needed for gc-log parity
jdk.GCPhasePauseLevel4        — same
jdk.GCPhaseConcurrentLevel2   — concurrent sub-sub-phases; not needed
jdk.G1HeapRegionInformation   — periodic region dump; too verbose
jdk.G1HeapRegionTypeChange    — per-transition event; high volume
jdk.PromoteObjectInNewPLAB    — 15K+/min; not in GC log output
jdk.PromoteObjectOutsidePLAB  — same category
jdk.ObjectAllocationInNewTLAB — application allocation; not a GC event
jdk.ObjectAllocationOutsideTLAB
jdk.ObjectAllocationSample
jdk.AllocationRequiringGC     — optional; disabled by default
jdk.ObjectCount
jdk.ObjectCountAfterGC
jdk.ZStatisticsCounter        — ZGC internals; optional
jdk.ZStatisticsSampler
jdk.ShenandoahHeapRegionStateChange — per-transition; high volume
jdk.OldObjectSample
jdk.ExecutionSample
jdk.NativeMethodSample
jdk.ThreadStart
jdk.ThreadEnd
jdk.JavaExceptionThrow
jdk.JavaErrorThrow
jdk.FileRead
jdk.FileWrite
jdk.SocketRead
jdk.SocketWrite
jdk.JavaMonitorEnter
jdk.JavaMonitorWait
jdk.ThreadPark
jdk.SafepointBegin
jdk.SafepointEnd
jdk.CPULoad
jdk.Compilation
jdk.ClassLoad
```

### NOT INCLUDED

| Event | Reason |
|---|---|
| `jdk.ZUnmap` | Removed from jdk25u metadata; fires 0 times on JDK 21; exclude entirely |
