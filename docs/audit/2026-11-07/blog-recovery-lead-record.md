# Blog recovery — Lead coordination record

## Authority and ownership

Actual `team_task_get(task-12)` returns revision 12, owner `lead`, status `in_progress`, no blockers; the fresh Blog writer retains exclusive application/report scopes until closing. Lead has not edited the Blog application, its authored manifest/lock, or its tests. No browser or real backend/DB operation is authorized by this record.

## External DOM tooling

Prior `BLOG_TEST_JSDOM_PATH` module location was not recovered from memory or the current test documentation. Searches of the Astral subtree and bundled Node packages did not find an existing jsdom package. Lead therefore installed **jsdom 26.1.0** with the bundled Node/pnpm, `--ignore-scripts --prefer-offline`, only under the generated ignored [audit tooling directory](<../../../target/audit-blog-dom-deps-20261107>). The installer exited 0; [immutable receipt](<../../../target/audit-blog-dom-dependency-install-20261107.log.utf8.log>). pnpm also used its automatically selected public content-addressable cache at `F:\.pnpm-store\v11` (22 reused / 17 downloaded, 39 linked packages); no global store setting was changed. Do not delete this shared cache as if exclusive ownership had been established. This does not change application/global package-manager settings and does not constitute browser acceptance.

Approved existing module for the Blog public React suite:

```powershell
$env:BLOG_TEST_JSDOM_PATH = 'F:/qtMusic/astral/target/audit-blog-dom-deps-20261107/node_modules/jsdom'
```

The version was read from the actual installed package. A dedicated [synthetic DOM load probe](<../../../target/audit-blog-dom-dependency-smoke-20261107.cjs>) runs with the pinned Node runtime; no network resources or real browser are involved. Blog may reuse this existing module for complete, unfiltered React gates. Keep the fullscreen replacement limited to that external platform capability; preserve both ancestor positives, unrelated positive, edited-item and descendant exclusions. Do not replace those assertions with old Edge/CDP claims, skip/filter them, or count Node geometry as browser proof.

## Baseline/spec disposition

[SPEC](<SPEC.md#L41-L47>) now corrects the earlier false “original math insertion” factual claim after the independently full-read committed component. Lead separately extracted and continuously read [committed renderer](<../../../target/audit-blog-renderer-baseline-20261107.txt.utf8.log>) lines 1–247: Marked/GFM with code/image/table/heading hooks, no dedicated math extension in that file. This is bounded source evidence, not a claim of all-site/runtime math absence. Retain any actual current math functionality; no invented RED or new math feature is required merely to satisfy the withdrawn baseline claim.

Original H1–H5, 120 Emoji, 17 syntax entries, 15 direct actions and bidirectional proportional scroll remain the fixed editor parity baseline; a current H6 addition must be labelled an extension, not claimed as original H6. All other functional preservation and original public assertions remain mandatory. Correct README/report wording and perform fresh actual gates; old writer claims are not inherited GREEN.
