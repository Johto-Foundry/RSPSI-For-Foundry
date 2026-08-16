# RSPSI for Foundry

Private Foundry-maintained build of the open-source RSPSi map editor.

This repository starts from the upstream RSPSi codebase and intentionally retains only the cache plugins useful to Foundry:

- `OSRSPlugin`
- `OSRS317Plugin`
- `Plugin317`

The initial baseline intentionally preserves the upstream Java 8 / JavaFX-compatible runtime and renderer so performance work can be measured independently from a runtime migration.

## Upstream

Original project: `RSPSi/RSPSi`

RSPSi is distributed under the MIT License. The original copyright and license notice are retained in `LICENSE`.
