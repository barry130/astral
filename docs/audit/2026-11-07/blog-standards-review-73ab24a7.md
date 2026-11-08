# Blog Standards 独立评审 — 73ab24a7

基线 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`；HEAD 相同，提交差集为空。仅审冻结 WIP 与未跟踪 authored 文件；**部分覆盖，不是全量 Standards 通过**。

## 文档规则偏差

- **P2：认证 Cookie 默认策略被降级。** 新增 [bff.ts:133–138](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/astral-blog/src/lib/bff.ts#L133-L138>) 重建 `satoken` 时固定 `sameSite: 'lax'`，即使上游为 Strict 也丢弃其策略；[登录路由:71–72](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/astral-blog/src/app/api/auth/login/route.ts#L71-L72>) 已接入。[集成规范:195–250](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/INTEGRATION_GUIDE.md#L195-L250>) 规定管理认证默认 Strict，拆域例外为 None+Secure；未见 Blog 的 Lax 例外。建议同源 BFF 默认 Strict，特殊策略显式配置并记录依据。此项是规范偏差，未断言 CSRF 可利用。

## 启发式异味（非文档硬违规）

- **P3 / Duplicated Code：上游失败归一重复。** [导出路由:38–48](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/astral-blog/src/app/api/admin/export/[...path]/route.ts#L38-L48>) 新增的业务失败判定、`code/errorCode/msg/data` 投影与 HTTP 选择，重复 [bff.ts:163–172](<F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a/snapshot/astral-blog/src/lib/bff.ts#L163-L172>)。修改共同失败策略需两处同步。建议复用 `upstreamFailure`；若需区分导出文案，仅参数化消息，不复制分支。

Blog 的服务端 fetch/BFF 例外、字典原值回退和真实 Dialog 容器已考虑；未把旧配色、格式化或文件长度当新增缺陷。Spec 合规不抵消以上 Standards 结论。

## 实际读取与验证 manifest（正文之外）

以下范围为实际 `read` 的并集；每次调用不超过 80 行。FULL 仅指列出的单文件，不能外推至整个候选。路径缩写仅在此代码块内使用。

```text
C = F:/qtMusic/astral/target/audit-blog-candidate-20261107-73ab24a7-d9b6-4759-8831-e1f3a0a9f48a
S = C/snapshot

C/tracked-working-tree.patch: 1–80,324–483,690–929,1241–1320,2261–2340,
                              4367–4446,4461–4540,7366–7445 / 9003; PARTIAL
C/untracked.txt: 1–33 FULL
C/status.txt: 1–103 FULL
C/commits.txt: empty FULL
C/fixed-ref.txt, C/head.txt: 1 FULL each
C/verification.json: 1–160,646–725 / 725; PARTIAL
Diff-header/hunk grep and path discovery were navigation only, not semantic coverage.

Standards read under S:
AGENTS.md: 247–326 / 534
 docs/UI_GUIDE.md: 1–80 / 249 (scope explicitly astral-front/src/**)
 docs/CODING_GUIDE.md: 77–156 / 207
 COMPONENTS_GUIDE.md: 176–220 / 496
 PLUGIN_GUIDE.md: 111–155 / 237
 INTEGRATION_GUIDE.md: 195–274 / 301

All following are under S/astral-blog and FULL from line 1:
README.md: 173; next.config.ts: 13; package.json: 51
src/lib/cms-status.ts: 21; editor.ts: 232; backend.ts: 46; bff.ts: 174
src/lib/client-request.ts: 29; admin-route.ts: 85; rate-limit.ts: 94
src/lib/auth-server.ts: 77; session.ts: 86; result.ts: 53
src/lib/cms.ts: 399; cms-validation.ts: 219; comment.ts: 38
src/lib/markdown-insert.ts: 15; markdown-tools.ts: 143; site-url.ts: 39; url.ts: 48
src/components/ui/dialog-portal.tsx: 9; dialog.tsx: 142; popover.tsx: 42
src/components/ui/badge.tsx: 39; alert.tsx: 63
src/components/editable-article.tsx: 658; session-provider.tsx: 169
src/components/login-dialog.tsx: 236; private-badge.tsx: 25
src/components/comments-section.tsx: 66; comment-form.tsx: 164
src/app/api/comment/route.ts: 61
src/app/api/auth/login/route.ts: 83; logout/route.ts: 37; session/route.ts: 35
src/app/api/admin/meta/route.ts: 48; export/[...path]/route.ts: 68
src/app/category/[slug]/page.tsx: 126; tag/[slug]/page.tsx: 95
 tests/source-manifest.cjs: 35

Not read semantically: remaining source/config/lock/tests, remaining patch and guide ranges.
No repair report or gate status was accepted as independent semantic proof.

Frozen-input verification:
129 sealed checkedFiles independently hashed (118 Blog files + 11 supporting files);
all SHA256 and byte counts match verification metadata; 0 mismatches.
Independent whole-snapshot digest:
  algorithm = SHA256(sorted(path + NUL + sha256 + NUL + bytes + LF))
  value = 9dd8687e668a6b03735594adf4f61c11efa4aa38e72478e86242c358706ae554
Declared source aggregate d0f3718da427be53de894365a600ae0a35ba9c08112acba6cd30fa21aa15b8c3
was not independently reproduced; individual sealed-file comparisons are the evidence.
Initial metadata fingerprints (rechecked identical):
patch = 01fdf3bdfaf8a89a6d8b1c84f8ac12ba24d9b6c7f055963462e3024d94d47fb7
untracked = f2b32caf2922912a6fa19f664afedba8a6ced43761611b8819495c7e7340c521
status = 051dcdf40d4bedc8923c14938a96a30b71523f52329ed09c67048be3caef227a
verification = 6d4d1b89028a5b825e9399646a537ad906cc01ad2df6464fac868dee9b1d1746
```

## 收口

1. 文档偏差 P2×1；启发式异味 P3×1，均限于实际读取证据。
2. 未执行测试/构建/网络/数据库/服务启动，也没有后台 job 或子代理。
3. 未调用浏览器、CDP、Edge、Playwright、AutoGLM 或其 bootstrap/诊断/重试；browserAccepted=false 不授权补做。
4. 因零网络限制未刷新 Web Interface Guidelines；静态源码不能证明滚轮、触摸热区、对比度或运行时行为。
5. 仅交付本 Standards 报告；不宣称全仓完成、Spec 通过或双轴最终闭环。
