# STANDARDS — bounded candidate 60863adc

## Scope and direct evidence
Fixed point and frozen HEAD: `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`. Captured committed diff/commits are empty under the authorized dirty-WIP convention, **not** an empty review. Actual coverage: [tracked patch](<../../../target/audit-common-std01-reviewed-candidate-20261107-60863adc-20d0-40e5-892f-e8d99e25e172/actual-tracked-wip.patch>) (26 lines, one hunk, `@@ -95,17 +95,16 @@`) plus the entire untracked [CommonUtilsTest](<../../../astral-common/src/test/java/com/astral/common/util/CommonUtilsTest.java#L1-L137>).

Fresh own reads reached unpruned EOF: current production/test 155/137 lines; HEAD-baseline production 156 (test absent); predecessor production/test 155/137; both live sources 155/137; frozen [AGENTS](<../../../target/audit-common-std01-reviewed-candidate-20261107-60863adc-20d0-40e5-892f-e8d99e25e172/norms/AGENTS.md>) 534 and [CODING_GUIDE](<../../../target/audit-common-std01-reviewed-candidate-20261107-60863adc-20d0-40e5-892f-e8d99e25e172/norms/docs/CODING_GUIDE.md>) 207. Forty read calls, all contiguous within each file and ≤80 lines, are logged in the [JSON evidence](<core-logsanitizer-std01-standards-60863adc.json>). Native/hash parsing is not semantic coverage. No Spec reviewer report was inspected; incidental historical mentions in the allowed context were excluded.

## Per-source conclusions
- **Production hunk:** no hard documented-standard breach or actionable POSSIBLE baseline smell. Both format adapters continue sharing `isSensitiveKey`; only the two value-marker exemptions and adjacent explanatory comments change. No abstraction or new dependency is introduced.
- **Whole untracked test:** no hard breach or actionable POSSIBLE smell. Both fixture locations, lines 123/131, now use `admin:audit:demo:view`, matching AGENTS:328 exactly: “**权限编码规范**：`端:域:资源:操作[:范围]`，**全小写**、冒号分隔。” The three inheritance checks remain. The predecessor’s three-part fixture is not a current finding. These nested, non-Spring test fixtures do not rename a deployed permission or require a migration. Splitting the small utility regression class is optional, not a mandatory fix.

Tool-enforced formatting/import/duplication checks are excluded. Historical captured gates report narrow Common formatting success and Common9/Auth3/content8/tool2/requester6 = 28 tests with zero failures/errors/skips; none were rerun.

## Integrity and verdict
Before/after samples at `2026-11-07T11:47:43.1050985Z` / `2026-11-07T11:50:42.0027294Z` match for all 17 metadata entries, including live bytes and native timestamps. A final `2026-11-07T11:58:53.6952418Z` resample also matched all 17 entries. Exact UTC7/100ns/ticks strings are recorded; endpoint equality is **not a continuous lock**.

**Bounded STANDARDS PASS; axisAccepted=true; hard breaches 0; POSSIBLE smells 0; worst within STANDARDS: none.** No coverage gaps within this axis. No whole-build/HTTP/AOP/database/browser/whole-audit acceptance; task-33, goals, staging, commits and pushes remain untouched.
