# Standards — frozen Common F01 (two sources)

**Bounded verdict: FAIL — 1 P3 hard-rule finding; 1 P3 POSSIBLE judgment. Worst within Standards: P3.** This is not core188, whole-repository, runtime, or Spec acceptance.

## Hard documented finding

**STD-01 (P3): invalid permission-code shape in the added test fixture.** [CommonUtilsTest:123–131](<F:/qtMusic/astral/target/audit-common-reviewed-candidate-20261107-7e89c485-d486-49ec-8e16-09bb6b0d50e6/current/astral-common/src/test/java/com/astral/common/util/CommonUtilsTest.java#L123-L131>) expects `"admin:demo:view"` and declares `@RequiresPermission("admin:demo:view")`. These three segments omit one mandatory domain/resource/action component. [Frozen AGENTS:249](<F:/qtMusic/astral/target/audit-common-reviewed-candidate-20261107-7e89c485-d486-49ec-8e16-09bb6b0d50e6/norms/AGENTS.md#L249>) applies the rules to all additions/changes; [§5:326–335](<F:/qtMusic/astral/target/audit-common-reviewed-candidate-20261107-7e89c485-d486-49ec-8e16-09bb6b0d50e6/norms/AGENTS.md#L326-L335>) requires `端:域:资源:操作[:范围]`. No fixture exception is documented. A later authorized revision should change both literals consistently to a valid four-segment synthetic code while preserving inheritance assertions. This is a convention defect in empty fixtures, **not** a demonstrated production authorization bypass.

## Judgment only

**POSSIBLE Divergent Change (P3):** [CommonUtilsTest:18–128](<F:/qtMusic/astral/target/audit-common-reviewed-candidate-20261107-7e89c485-d486-49ec-8e16-09bb6b0d50e6/current/astral-common/src/test/java/com/astral/common/util/CommonUtilsTest.java#L18-L128>) combines `pageQueryClampsUnsafeValues`, `csvExportEscapesFormulaAndRfc4180`, sanitizer, patch, IP, password, and annotation-inheritance concerns. Separate utility-focused test classes could isolate future reasons to change. This is optional; no mandatory speculative refactor is requested. All twelve Fowler assessments are explicit in the JSON; repo rules override heuristics.

## Evidence and limits

The actual [tracked patch](<F:/qtMusic/astral/target/audit-common-reviewed-candidate-20261107-7e89c485-d486-49ec-8e16-09bb6b0d50e6/actual-tracked-wip.patch>) contains one `@@ -95,17 +95,16 @@` hunk: two MASK skips removed; three adjacent comment lines become two. Regex, key policy, and literals are unchanged. The entire untracked test, all nine methods, and source baseline were read—not merely the two new JSON/form tests.

Own coverage: 39 unpruned ≤80-line reads; 35 complete-corpus reads plus four explicitly partial historical-receipt reads. Current sources: 292 raw / 262 nonblank / 13,194 bytes. Full source materializations including baseline: 448 / 407 / 19,798. Two norms and two context documents reached EOF. Native/snapshot pre/post fingerprints agree; endpoint sampling is not continuous immutability.

Historical receipts record JSON 8/1→8/0, form 9/1→9/0, joint27 all-zero and three format exits0; no gates were rerun. No Git/build/test/browser/network action, Spec review, source edit, staging, commit, or push occurred. Local commit and final no-skip/whole-goal acceptance remain pending. [Machine evidence](<F:/qtMusic/astral/docs/audit/2026-11-07/core-logsanitizer-standards-7e89c485.json>) preserves attribution and proof limits.
