---
title: "Cookbook: Fleet-Wide GC Monitoring"
---

# Cookbook: Fleet-Wide GC Monitoring

**Situation:** You run cjfr on 20+ servers and want a consolidated view of GC health
across the fleet — pause percentiles, allocation rates, heap trends — without copying
every recording to a central machine.

---

### On each server — start rotating recording

Use the smallest agent JAR that fits your deployment. For a known Linux host:

```shell
# Download once per server (or bake into your base image)
curl -L -o /opt/cjfr/cjfr-agent.jar \
  https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data-linux-amd64-inflaterless.jar

# Start at JVM launch — keep 24 hours at 100 MB/file
java -javaagent:/opt/cjfr/cjfr-agent.jar=\
'start,/var/rec/app_$index.cjfr,--rotating,--max-files=24,--max-size=100m,--max-duration=1h' \
  -jar myapp.jar
```

Using `--max-duration=1h` with `--max-files=24` gives you exactly 24 hourly files,
which makes it easy to reason about what window is on disk.

---

### Copy recordings to an analysis host

```shell
# Pull the last N files from each server
for host in server1 server2 server3; do
  mkdir -p ./fleet/$host
  rsync -av "$host:/var/rec/app_*.cjfr" "./fleet/$host/"
done
```

---

### Summarise per server

Query each server's most recent file for a quick GC summary. The GC Summary section
is only produced for single-file queries — use the most recent file as a representative:

```shell
for host in fleet/*/; do
  echo "=== $host ==="
  # Use most recently modified file for the GC summary
  latest=$(find "$host" -maxdepth 1 -name 'app_*.cjfr' -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-)
  cjfr summary --short "$latest" 2>&1
done
```

---

### Aggregate: find the worst-behaved server

The `--json` output includes a `gc` section with `p95Micros` and `maxMicros` pause stats
(values in microseconds). Query the most recent file per server:

```shell
for host in fleet/*/; do
  server=$(basename "$host")
  latest=$(find "$host" -maxdepth 1 -name 'app_*.cjfr' -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-)
  echo -n "$server p95_pause_ms="
  cjfr summary --json "$latest" \
    | jq '(.gc.p95Micros // 0) / 1000'
done
```

---

### Drill into the worst host

```shell
# After identifying server2 as the outlier:
cjfr summary --gc-percentile=90 fleet/server2/app_0.cjfr \
  fleet/server2/app_1.cjfr fleet/server2/app_2.cjfr

cjfr inflate --gc-percentile=90 fleet/server2/app_0.cjfr \
  fleet/server2/app_1.cjfr \
  fleet/server2/worst-pauses.jfr
```

---

### Adjusting limits live (no restart)

If disk usage grows unexpectedly on a server, shrink the ring buffer without stopping
the recording. Run these commands on the server (or via `ssh server1`):

```shell
cjfr agent myapp set-max-files 10
cjfr agent myapp set-max-size 50m
```
