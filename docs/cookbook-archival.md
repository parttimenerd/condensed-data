---
title: "Cookbook: Archival Pipeline"
---

# Cookbook: Archival Pipeline

**Situation:** You have a collection of old `.jfr` files (or existing `.cjfr` files
with LZ4FRAMED compression) and want to archive them long-term at maximum compression.

---

### Shortcut: `archival-max`

For the common "smallest possible file, read time irrelevant" case, use the
`archival-max` shortcut instead of spelling out the config and level:

```shell
cjfr condense --condenser-config archival-max recording.jfr archive.cjfr
```

`archival-max` expands to `reduced` data reductions plus `MAX_COMPRESSION` (LZ4FRAMED
level 17). It is equivalent to:

```shell
cjfr condense --condenser-config reduced \
  --compression-level MAX_COMPRESSION recording.jfr archive.cjfr
```

This is the recommended choice for long-term archival. GZIP (`--compression=GZIP`)
produces only ~1% smaller files at the cost of slower reads — there is no practical
reason to use it over `archival-max`.

The rest of this page shows the individual knobs (`--condenser-config`,
`--compression`, `--compression-level`) for when you want finer control.

---

### Condense a folder of JFR files

```shell
# Entire directory → single .cjfr alongside the folder
cjfr condense /data/jfr/2024-05-24/
# → /data/jfr/2024-05-24.cjfr

# With maximum reduction config
cjfr condense --condenser-config=reduced /data/jfr/2024-05-24/
```

---

### Batch archival script

```shell
#!/bin/bash
# Condense all JFR files under a year directory, delete originals if successful
YEAR_DIR=/data/jfr/2024

find "$YEAR_DIR" -name "*.jfr" | while read -r jfr; do
  cjfr_out="${jfr%.jfr}.cjfr"
  if cjfr condense --condenser-config=archival-max "$jfr" "$cjfr_out"; then
    # Verify the output is readable before deleting the original
    if cjfr summary --short "$cjfr_out" > /dev/null 2>&1; then
      rm "$jfr"
      echo "Archived: $jfr → $cjfr_out"
    else
      echo "Verification failed, keeping original: $jfr"
      rm "$cjfr_out"
    fi
  fi
done
```

!!! tip "The summary check validates integrity too"
    `cjfr summary` verifies the file's whole-file CRC32 before reading, so the
    verification step above also catches a `.cjfr` that was written or copied
    incorrectly — not just one that fails to parse.

---

### Condense a ZIP archive

```shell
cjfr condense recordings.zip archive.cjfr
```

!!! note "ZIP and folder inputs use `default` config"
    When condensing a ZIP or directory, `cjfr condense` defaults to the `default`
    condenser config (conservative lossy). For archival, pass
    `--condenser-config=archival-max` explicitly:

    ```shell
    cjfr condense --condenser-config=archival-max recordings.zip archive.cjfr
    ```

---

### Verify and inspect an archived recording

```shell
# Check it opens and print summary
cjfr summary --short archive.cjfr

# What events are inside?
cjfr summary archive.cjfr

# Inflate a time slice back to JFR when needed
cjfr inflate --start="2024-05-24 12:00:00" --duration=1h \
  archive.cjfr slice.jfr
```

---

### Expected compression ratios

| Input | Config | Typical result |
|---|---|---|
| gc_details-heavy JFR | `archival-max` (`reduced` + MAX) | ~1–5% of original |
| gc_details-heavy JFR | `default` + HIGH | ~3–15% of original |
| Sparse gc-only JFR | `archival-max` (`reduced` + MAX) | ~5–10% of original |

*Ranges from renaissance benchmark measurements. Actual results depend on GC
frequency, thread count, and allocation rate.*

---

### Choosing the right config

| Goal | Config | What it does |
|---|---|---|
| Smallest archive, some data loss acceptable | `archival-max` | Full `reduced` data reductions + max LZ4 compression |
| Smallest archive, keep all data | `lossless --compression-level MAX_COMPRESSION` | No data removal, max LZ4 compression |
| Quick condense with moderate compression | `default` | Conservative lossy reductions, default LZ4 |

The `reduced` config drops stack frame line numbers, aggregates allocation samples,
and removes redundant address fields — acceptable for most post-mortem archival.
Use `lossless` if you need to retain full original fidelity.
