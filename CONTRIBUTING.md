# Contributing

Thanks for your interest in improving `kotlin-resolve`. This document describes the
practical setup for development.

## Development setup

Requirements:
- JDK 21+
- Gradle 8.10+ (the wrapper handles this; you only need a JDK)

```bash
# 1. Build & install core lib to local Maven
cd kotlin-resolve
./gradlew publishToMavenLocal

# 2. Build & install KSP processor
cd ksp-processor
./gradlew publishToMavenLocal

# 3. Run unit tests (reflection fallback path)
cd ..
./gradlew test

# 4. Run benchmarks (KSP path)
cd benchmarks
./gradlew run --rerun-tasks
```

## Project layout

| Directory | Purpose |
|---|---|
| `src/main/kotlin/` | Core runtime: `Resolver`, `DataLoader`, annotations, metadata |
| `src/test/kotlin/` | Unit tests — currently run on the **reflection fallback** path |
| `ksp-processor/` | KSP plugin generating zero-reflection adapters |
| `benchmarks/` | 6-case benchmark mirroring `pydantic-resolve/benchmarks/` |
| `examples/demo/` | Minimal standalone consumer |

## Tests

11 unit tests live under `src/test/`. They currently execute via reflection fallback
(the parent project does not apply the KSP plugin). To exercise the KSP-generated
adapters, run the `benchmarks` task — it applies KSP and the generated code is
what the benchmark actually calls.

A future improvement: add a `kspTest` source set so unit tests cover both paths.

## Style

- 4-space indent, no tabs
- One top-level declaration per file (with exceptions for tightly-coupled helpers)
- Public API in `data class` / `class`; internals may use `private class`
- Comments explain **why**, not **what**

A `ktlint` / `detekt` config will land before the 0.2 release.

## Pull requests

- Keep PRs focused — one feature or fix per branch
- Include a test that fails before your change and passes after
- Run `./gradlew test` (and ideally the benchmark) before requesting review
- Use [Conventional Commits](https://www.conventionalcommits.org/) for messages

## Roadmap

See [ROADMAP.md](ROADMAP.md) for the prioritised feature list.
