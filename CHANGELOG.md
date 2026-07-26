# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Per-bug detail lives in [BUGS.md](BUGS.md); this file summarizes user-visible changes.

## [Unreleased]

## [0.1.1] - 2026-07-26

### Added
- Native `cjfr view` that reimplements the JDK `jfr view` named views (parsing the on-system
  `view.ini`), so `cjfr view` is a drop-in replacement rendering output that matches `jfr view`.
- `LAST_BATCH` view aggregator and a synthetic `stackTrace.topNotInitFrame` field for site views.
- Combiners for `G1HeapRegionInformation`, `G1HeapRegionTypeChange` (grouped by `(from,to)`),
  `JavaMonitorEnter`/`Wait`, `JavaErrorThrow`, and lossless `ThreadPark` grouping.
- Deduplication of `ThreadAllocationStatistics` when allocated bytes are unchanged.

### Changed
- Lossless combiners moved into the `default` preset; `default` is now the default config for agents.
- Flagless fast path: the CLI opens `jdk.jfr.consumer` via a `Launcher-Agent-Class` so the
  positional `RecordedObject` accessor speeds up `condense` with zero user flags.

### Fixed
- **Agent recordings lose their timezone (Bug 290):** the agent never set `gmtOffset`, so inflated
  files rendered in UTC. Now seeded from the recording JVM's default zone.
- **Agent-condensed files fabricate a corrupt numeric event type (Bug 289):** ActiveSetting id
  remapping fell back to a bare number, breaking `jfr`/JMC. Fixed by seeding event types from
  `FlightRecorder` and hardening inflate against legacy files.
- **Timezone/`gmtOffset` preservation across inflate (Bug 264/265):** including summer DST offsets.
- **Lossless correctness (Bugs 275–284):** preserve per-entry startTimes and `@Timespan` sentinels;
  stop collapsing periodic time-series into combined-event timestamps.
- **Agent config (Bug 285/286):** honor `--condenser-config`; keep structural combiners off during
  on-the-fly config.
- `cjfr view` no longer dumps the raw `StackTrace` struct when a view selects `stackTrace` (Bug 288).
- Assorted agent fixes: correct `ownJAR()` resolution, `--rotating --max-size` triggering on
  compressible data, and passing the condenser config through to `BasicJFRWriter`/`StartMessage`.

### Performance
- `cjfr view` on large `.jfr` inputs: skip full-scan materialization and struct reconstitution on
  the `.cjfr` read path for named/event-filtered views (45s → ~1.5s).
- Shrunk `GCPhaseParallel` encoding; dropped unnecessary `GCWorker` event threads from `default`.

## [0.1.0] - 2026-07-10

Initial tagged baseline (`latest`): `.cjfr` format hardened with header validation and a
whole-file CRC32.
