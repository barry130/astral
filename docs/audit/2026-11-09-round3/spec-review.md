# Round 3 independent Spec review

Review date: 2026-11-09. Repository: `F:/qtMusic/astral`. Axis: Spec.

## Fixed references and read scope

- Product: `e0d9236df7fc641081707a70416270ffb35de170`.
- Incremental baseline: `587b4ae215375dd745dde0630ddbd2de5c94efbf`.
- Original audit baseline: `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`.
- Both baseline-to-product three-dot diffs resolve and are nonempty; the original-to-product commit list was reviewed. The incremental diff contains 23 files, 1,322 insertions and 385 deletions.
- Actual source reading covers every incremental changed hunk, both complete new concurrency regression classes, and necessary callers, controllers/services, generated entity constraints, schemas/migrations, event/channel definitions and frontend SMS API contracts.
- Requirements come from [SPEC.md](../2026-11-07/SPEC.md), the continued “还有没有其他问题，有的话继续” instruction, and [TEST-SEAMS.md](TEST-SEAMS.md). Broader coverage is inherited from [CONTINUATION.md](../2026-11-09/CONTINUATION.md) and [verification.json](../2026-11-09/verification.json); this round does not claim a fresh deep read of the entire original-to-product scope.

## Findings and verdict

**Spec PASS: 0 remaining confirmed Spec findings in the incremental scope.** No missing or partial requirement, incorrectly implemented requirement, or unrelated expansion was confirmed.

- **Permission integrity:** create/update validate the effective permission, including fields retained by partial updates. Assignment, batch replacement, App synchronization and permission deletion share database permission-row locks; ordered acquisition preserves the existing role-before-permission protocol. Reference checks occur while deletion holds the same lock. Public persistence and real PostgreSQL interleaving regressions support the closure.
- **Notification-rule integrity:** public service transactions lock the parent before reading or modifying children. Full-save and explicit-null behavior remain; missing/deleted updates reject with `NOTIFY002`, while deletion remains idempotent. The two PostgreSQL interleavings inspect all three tables through the accepted observation boundary and cover orphan-recipient prevention.
- **SMS patch/cache behavior:** omitted fields retain their saved values; required effective values, registered event/SMS compatibility, uniqueness and field bounds remain validated. Updates ignore client ID/timestamps, permit optional empty strings, and leave a valid empty patch unchanged. Cache eviction follows commit; rollback and uncommitted-reader regressions verify its transaction boundary. Generated `@Size` constraints permit partial requests. The merged-template event remains SMS-compatible; its required EMAIL channel applies to rules.

The regressions observe public behavior and persisted outcomes through the documented seams, rather than merely reproducing wrapper shapes. These closures satisfy SPEC lines 20–21 and 35; parent-run Java/code-generation evidence supports lines 36 and 55. The Standards verdict remains a separate axis. This conclusion is scoped to the reviewed changes and inherited audit coverage.

## Evidence and limits

The following runs were performed by the parent, not this reviewer; their saved logs were independently inspected.

| Evidence | Result |
| --- | --- |
| [Final reactor](../../../target/round3-final-reactor.log) | 139 suites / 881 tests; 0 failures, errors or skips; completed `2026-11-09T19:13:37+08:00`; actual generation of 34 entities. |
| [Focused regression run](../../../target/round3-focused-green2.log) | 11 suites / 102 tests; 0 failures, errors or skips; completed `2026-11-09T18:59:39+08:00`; includes 9 role/permission and 2 rule PostgreSQL concurrency cases. |
| [Frozen tested-source snapshot](../../../target/round3-tested-source-snapshot.json) | All 23 source hashes matched at `2026-11-09T19:27:06+08:00`; HEAD remained the product SHA. |
| [Quality run](../../../target/round3-final-quality.log) | Build succeeded. Parent reports 248 Medium / 0 High SpotBugs findings and 379 Checkstyle warnings / 0 errors, with diagnostics assessed separately. |

H2 persistence and an in-memory SMS SPI provide isolated behavior evidence; real PostgreSQL provides concurrency evidence. These are not HTTP, browser, production or external-delivery acceptance. Browser validation and real SMS/SMTP/model delivery were not performed. The existing browser authorization boundary in SPEC lines 46–47 remains applicable.
