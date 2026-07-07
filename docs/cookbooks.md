---
title: Cookbooks
layout: default
nav_order: 8
has_children: true
---

# Cookbooks

Opinionated, scenario-driven guides for the most common `cjfr` use cases.
Each cookbook explains what to do and why, not just the commands.

---

- [GC Regression Hunt]({% link cookbook-gc-regression.md %}) — before/after comparison, worst-pause extraction, allocation event analysis
- [Fleet-Wide GC Monitoring]({% link cookbook-fleet-monitoring.md %}) — rsync pull, batch summary, JSON aggregation, live limit tuning
- [Container and Sidecar Deployment]({% link cookbook-container.md %}) — inflaterless agent in Docker, Kubernetes init-container pattern
- [Archival Pipeline]({% link cookbook-archival.md %}) — GZIP re-compression, batch script with verification, ZIP condensing
