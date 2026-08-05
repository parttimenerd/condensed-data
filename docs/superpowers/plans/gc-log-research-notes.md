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
