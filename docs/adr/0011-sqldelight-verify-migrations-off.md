# ADR 0011: `verifyMigrations` stays off until the schema's first post-release change

**Status:** Accepted; revisit at the first migration

## Context

§11 configures SQLDelight with `verifyMigrations = true`. The build sets
`verifyMigrations.set(false)` (`core/build.gradle.kts`).

What that flag does is narrower than its name suggests: it replays the
`.sqm` migration files against **snapshotted schema versions** (`.db` files
produced by SQLDelight's `generateSchema` task) and fails if the result does
not match the current `.sq` definitions. It verifies migrations — not the
schema. With no migration files and no snapshot committed, there is nothing
for it to check, and turning it on buys no safety; it only asks for a
snapshot artifact to be committed and kept current for a schema that has
never migrated.

The v1 schema also cannot need a migration yet: nothing is released, so no
database exists in the wild at an older version.

## Decision

Leave `verifyMigrations = false` for now. Flip it to `true` — and commit the
initial schema snapshot in the same change — the first time the schema
changes after a release, which is the first moment a `.sqm` file exists and
the flag has something real to verify.

The trigger is recorded at the flag itself in `core/build.gradle.kts`, so the
next person to touch the schema meets it without having to find this ADR.

## Consequences

- Until that flip, a schema change is guarded by the domain tests and by
  §17.6's determinism guard, not by migration verification. That is the same
  coverage the project had before, and it is sufficient while every install
  creates its database from the current `.sq` files.
- The flip is deliberately coupled to the first migration rather than to a
  date or a milestone: doing it earlier would commit a snapshot that only
  ever needs regenerating, and doing it later would let the *first* real
  migration — the one most worth checking — go unverified.

## Alternatives considered

- **Turn it on now and commit a snapshot.** The snapshot would have to be
  regenerated on every pre-release schema edit while verifying nothing, which
  is precisely the kind of ceremony that gets deleted rather than maintained.
- **Edit §11 to say `false`.** Wrong direction: `true` is the right end
  state, and this is a "not yet", not a rejection.
