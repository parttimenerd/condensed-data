---
title: "Cookbook: Container and Sidecar Deployment"
---

# Cookbook: Container and Sidecar Deployment

**Situation:** You want to record GC data inside a Docker container with minimal
footprint and pull the `.cjfr` files out for offline analysis — without the container
needing `cjfr inflate` capability.

---

### Build the base image with the inflaterless agent

```dockerfile
FROM eclipse-temurin:21-jre

# ~1.5 MB agent JAR — no inflate, no JMC dependencies
ADD https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data-linux-amd64-inflaterless.jar \
    /opt/cjfr/cjfr-agent.jar

# Mount point for recordings
RUN mkdir -p /var/rec
VOLUME /var/rec
```

---

### Start with rotating recording

```dockerfile
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/cjfr/cjfr-agent.jar=\
start,/var/rec/app_$\{index\}.cjfr,--rotating,--max-files=5,--max-size=100m"
```

Or pass it at `docker run` time:

```shell
docker run \
  -v /host/recordings:/var/rec \
  -e JAVA_TOOL_OPTIONS="-javaagent:/opt/cjfr/cjfr-agent.jar=start,/var/rec/app_\$index.cjfr,--rotating,--max-files=5,--max-size=100m" \
  myapp:latest
```

!!! warning "Escape `$index` in shell strings"
    Inside double-quoted shell strings (including `docker run -e "…"` and
    Dockerfile `ENV` lines), the shell expands `$index` to empty before the JVM
    sees it. Use `\$index` (or single-quote the value) so the literal placeholder
    reaches the agent.

---

### Pull recordings and analyse on the host

The container only needs the inflaterless JAR. Inflation uses the full JAR on your
workstation:

```shell
# Copy from container
docker cp mycontainer:/var/rec/. ./recordings/

# Summary — no inflate needed
cjfr summary recordings/app_0.cjfr -i recordings/app_1.cjfr

# Inflate the interesting window
cjfr inflate --start="2024-05-24 14:00:00" --duration=1h \
  recordings/app_0.cjfr -i recordings/app_1.cjfr \
  recordings/incident.jfr
```

---

### For absolute minimum JAR size (~450 KB)

Use the minimal variant — LZ4FRAMED and GZIP, ProGuard-optimised:

```shell
condensed-data-linux-amd64-inflaterless-minimal.jar
```

The recording format is identical; any full JAR can inflate it.

---

### Kubernetes: volume-mount + init container pattern

```yaml
volumes:
  - name: gc-recordings
    emptyDir: {}
initContainers:
  - name: cjfr-agent
    image: curlimages/curl
    command:
      - sh
      - -c
      - |
        curl -L -o /agent/cjfr-agent.jar \
          https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data-linux-amd64-inflaterless.jar
    volumeMounts:
      - name: gc-recordings
        mountPath: /agent
containers:
  - name: myapp
    env:
      - name: JAVA_TOOL_OPTIONS
        value: "-javaagent:/agent/cjfr-agent.jar=start,/var/rec/app_$index.cjfr,--rotating,--max-files=10,--max-size=100m"
    volumeMounts:
      - name: gc-recordings
        mountPath: /var/rec
```
