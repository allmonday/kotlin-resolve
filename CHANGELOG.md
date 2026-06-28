# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial public release of `kotlin-resolve`, a Kotlin port of the core data-assembly
  framework from `pydantic-resolve`.
- Two-phase BFS resolver (`Resolver.resolve`) with concurrent coalesced DataLoader
  calls and bottom-up `@Post` execution.
- Zero-reflection code generation via `kotlin-resolve-ksp` compiler plugin.
- `Collector` / `SendTo` (child → parent aggregation) and `ExposeAs`
  (parent → child broadcast via `AncestorContext`).
- `DataLoader` with same-tick batching, `buildList` / `buildObject` helpers.
- Reflection fallback when KSP plugin is not applied.

### Performance
- 6 benchmark cases: 5 faster than the Python original; the heaviest
  (`large_dataset_post`, 2000 items) is ~14× faster.
