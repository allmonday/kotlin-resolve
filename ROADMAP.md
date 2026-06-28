# Roadmap

A prioritised list of work items, mirroring the `pydantic-resolve` feature set
where it makes sense on the JVM.

## P0 — Required for migration from Python

- [ ] **`mapper` post-processing** — auto-convert raw loader output to the field's
  declared type (mirrors `@mapper(func_or_class)`).
- [ ] **`loader_params`** — inject runtime parameters (tenant_id, user_id, …) into
  `DataLoader` instances via `Resolver(loader_params = ...)`.
- [ ] **Loader `_context` field** — let loaders read the Resolver context
  (mirrors `_context` on Python DataLoader subclasses).
- [ ] **`post_default_handler`** — class-level fallback that runs after every
  field-specific `@Post`.
- [ ] **Concrete exception types** — replace `error()` calls with the existing
  exception classes for predictable `catch` behavior.

## P1 — Power-user expectations

- [ ] **ER Diagram + Relationship** — `@ResolveEntity` + `@Relationship` annotations;
  KSP generates the relationship graph and synthesises `@Resolve` methods.
- [ ] **`AutoLoad(origin="...")`** — explicit relationship-to-field mapping on top
  of ER Diagram.
- [ ] **`ensure_subset`** — compile-time check that a View's fields are a subset
  of the source Entity.
- [ ] **`split_loader_by_type`** — one `DataLoader` instance per request-type set
  (used for column-pruning in ORM integrations).

## P2 — Ecosystem integrations

- [ ] **Ktor end-to-end example** — `/sprints` HTTP endpoint with Exposed loader.
- [ ] **Exposed Inspector** — generate `Relationship` declarations from
  `org.jetbrains.exposed.sql.Table` definitions.
- [ ] **Komapper Inspector** — same for Komapper entities.
- [ ] **Dokka API docs** published to GitHub Pages.

## P3 — Optional / unlikely

- [ ] **GraphQL integration** — bridge to `graphql-kotlin`, generating a schema
  from an ER Diagram.
- [ ] **MCP server** — expose the entity graph as a tool for AI agents.
- [ ] **`DefineSubset`** — JVM cannot build classes at runtime; would require KSP
  codegen of view classes from a declarative config. Defer until demand exists.
- [ ] **Kotlin Multiplatform (JS / Native)** — depends on demand.

## Engineering infrastructure

- [ ] `ktlint` + `detekt` config in `build.gradle.kts`
- [ ] GitHub Actions: test on push, snapshot-publish on main
- [ ] Test source set applying the KSP plugin (currently only benchmarks cover it)
- [ ] Maven Central publishing (requires Sonatype account + GPG)
- [ ] Semver tags + GitHub Releases with changelog
