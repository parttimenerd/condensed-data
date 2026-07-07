---
title: "Cookbook: Archival Pipeline"
layout: default
nav_order: 12
parent: Cookbooks
---

# Cookbook: Archival Pipeline

**Situation:** You have a collection of old `.jfr` files (or existing `.cjfr` files
with LZ4 compression) and want to archive them long-term at maximum compression.

---

### Condense a folder of JFR files

```shell
# Entire directory → single .cjfr alongside the folder
cjfr condense /data/jfr/2024-05-24/
# → /data/jfr/2024-05-24.cjfr

# With maximum reduction config
cjfr condense --condenser-config=reduced-default /data/jfr/2024-05-24/
```

---

### Re-compress with GZIP for long-term storage

GZIP gives a better byte-level ratio than LZ4 at the cost of slower reads.
For files that will be read rarely, the trade-off is worth it.

```shell
# Condense and compress in one pass
cjfr condense --condenser-config=reduced-default --compression=GZIP \
  recording.jfr recording.cjfr

# Re-compress an existing LZ4 .cjfr to GZIP
cjfr inflate recording-lz4.cjfr /tmp/recording.jfr
cjfr condense --condenser-config=reduced-default --compression=GZIP \
  /tmp/recording.jfr recording-gzip.cjfr
rm /tmp/recording.jfr
```

---

### Batch archival script

```shell
#!/bin/bash
# Condense all JFR files under a year directory, delete originals if successful
YEAR_DIR=/data/jfr/2024

find "$YEAR_DIR" -name "*.jfr" | while read -r jfr; do
  cjfr_out="${jfr%.jfr}.cjfr"
  if cjfr condense --condenser-config=reduced-default --compression=GZIP \
       "$jfr" "$cjfr_out"; then
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

---

### Condense a ZIP archive

```shell
cjfr condense recordings.zip archive.cjfr
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

| Input | Config | Compression | Typical result |
|---|---|---|---|
| gc_details-heavy JFR | `reduced-default` | GZIP | ~1–5% of original |
| gc_details-heavy JFR | `reasonable-default` | GZIP | ~3–15% of original |
| Sparse gc-only JFR | `reduced-default` | GZIP | ~5–10% of original |

*Ranges from renaissance benchmark measurements. Actual results depend on GC
frequency, thread count, and allocation rate.*
