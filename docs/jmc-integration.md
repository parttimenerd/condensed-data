# JMC Integration

`condensed-data` integrates with [JMC](https://github.com/SAP/jmc) as an OSGi bundle,
allowing JMC to open `.cjfr` files directly. The integration lives in
[`parttimenerd/jmc`](https://github.com/parttimenerd/jmc) on the `sap` branch.

## How it works

`CjfrEditor` intercepts the Eclipse editor open for `.cjfr` files, inflates the recording
to a temporary `.jfr` file using `WritingJFRReader.toJFRStream`, swaps the editor input,
then delegates to JMC's standard `JfrEditor`.

The `condensed-data` OSGi bundle is published with a `-jmc` classifier. This variant
**excludes** the shaded `org.openjdk.jmc.flightrecorder.writer` classes — JMC's own
`flightrecorder.writer` OSGi bundle supplies them at runtime. This means the writer
stays in sync automatically as JMC evolves, with no copy drift.

## Artifacts

| Classifier | Purpose | Writer classes |
|---|---|---|
| *(none)* | Standalone CLI / agent uber-jar | Shaded in |
| `-reader` | Embeddable reader library | Excluded (no writer) |
| `-jmc` | JMC OSGi bundle | Excluded — provided by JMC |

## Release checklist

When cutting a new `condensed-data` release for JMC consumption:

### 1. Bump the version

In `pom.xml`, update `<version>`:

```xml
<version>0.X.Y</version>
```

### 2. Publish all artifacts

```bash
# Publish the main jar + sources + javadoc to Maven Central
mvn -Ppublication deploy -DskipTests -P!jmc-test

# Publish the -jmc classifier jar
mvn -Pjmc-publication deploy -DskipTests -P!jmc-test

# Publish the -reader classifier jar (standalone reader library)
mvn -Preader-publication deploy -DskipTests -P!jmc-test
```

Each profile:
- requires GPG signing (needs `~/.gnupg` with the release key)
- requires `~/.m2/settings.xml` with `ossrh` server credentials
- publishes to Maven Central via Sonatype

Wait for Central to sync (~10–30 min) before updating the JMC fork.

### 3. Update the JMC fork

In [`parttimenerd/jmc`](https://github.com/parttimenerd/jmc) on the `sap` branch,
bump the version in `releng/third-party/pom.xml`:

```xml
<condensed-data.version>0.X.Y</condensed-data.version>
```

Also update the p2 target file
`releng/platform-definitions/platform-definition-2026-06/platform-definition-2026-06.target`:

```xml
<unit id="me.bechberger.condensed.data" version="0.X.Y"/>
```

Then rebuild the p2 repository:

```bash
cd releng/third-party
mvn p2:site
mvn jetty:run &   # serves the p2 site at http://localhost:8080/site
```

With the Jetty server running, refresh the target platform in Eclipse PDE and verify
JMC builds and opens a `.cjfr` file.

## OSGi wiring

The `me.bechberger.condensed.data` bundle declares:

```
Require-Bundle: org.openjdk.jmc.flightrecorder.writer
Export-Package: me.bechberger.*
Import-Package: *;resolution:=optional
```

The `org.openjdk.jmc.flightrecorder.cjfr` plugin bundle declares:

```
Require-Bundle: ...,
 me.bechberger.condensed.data,
 org.openjdk.jmc.flightrecorder.writer
```

Both bundles are included in `org.openjdk.jmc.feature.flightrecorder`.

## Writer compatibility

`BasicJFRWriter` uses `org.openjdk.jmc.flightrecorder.writer` API. If JMC adds new
API to the writer that `BasicJFRWriter` needs to call, update `condensed-data` first
and release a new version before updating the JMC fork. The SAP/JMC PR CI will catch
compile failures immediately.
