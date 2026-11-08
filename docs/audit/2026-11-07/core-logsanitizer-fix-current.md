# Core F01：部分 MASK 日志凭据绕过修复

## 已复现与最小修复

公开 [LogSanitizer.mask](<F:/qtMusic/astral/astral-common/src/main/java/com/astral/common/util/LogSanitizer.java#L93-L112>) 原先因值中含 MASK 或以 MASK 开头而跳过敏感值。合成 JSON `******CONFIDENTIAL_REMAINDER` 与表单 `******CONFIDENTIAL_FORM_REMAINDER` 均可留下可读凭据尾部。

仅删除两个 skip 条件并更新相邻注释；敏感键判定、正则及输出契约未改。固定替换仍幂等；输出不保证合法 JSON。

## 实际公开回归

- [JSON 用例:63–72](<F:/qtMusic/astral/astral-common/src/test/java/com/astral/common/util/CommonUtilsTest.java#L63-L72>)：实际 8 tests/1 failure RED →同用例 8/0 GREEN。
- [表单用例:74–84](<F:/qtMusic/astral/astral-common/src/test/java/com/astral/common/util/CommonUtilsTest.java#L74-L84>)：实际 9/1 RED →同用例 9/0 GREEN。
- F01 封存时点的原七项断言保留；只移除两个新增方法并做 CRLF→LF 后，与原封存逐字相等，SHA256 `a2ada2680eff3494c36891152f1bbfda6a784c467c57ce411800765e6aa16ce6`；后续夹具规范修正另列下节。
- 三个单文件窄格式化成功；格式后联合 Common9/Auth3/AI正文8/工具配置1/requester6 共27 tests，失败/错误/跳过均0。后续工具更新纵切会重新绑定自己的门禁版本，不倒改此历史收据。

## 当前 core188 差量与边界

[独立差量封存](<F:/qtMusic/astral/target/audit-core-logsanitizer-current-delta-seal-20261107-26c9c8e9-c5cb-41f5-be1d-11b1f0b57189/validation.json>) 核 F01 封存时点的原188源只有这两个源改变，186项精确保持。该时点19370 raw/17561 nonblank/934197 bytes；旧19348/17545/932996及原读者归因保留。

F01 封存源码摘要：Sanitizer `efd9234628ac4906a89accebe143f57d2ab810de9e897c923d493343d8722b2b`；CommonUtilsTest `fb39ee01e4c46cc8383f20107a41bce5f4830e0d1c64efb39ffe3bc97ba25381`。两个当前源均已由 Lead 按≤80行读至EOF。封存含两个原/当前版本指针、两条真实RED/GREEN、三次窄格式及27联合日志，原封存不覆盖。

此处只认证公开纯函数与所列有限回归；不认证所有日志格式、实际操作日志/AOP/异步/HTTP/数据库/浏览器，也不是 Root 全188语义、整轴、最终无skip生成器或全仓验收。新跟进候选的独立修复审阅与本地提交仍待；未暂存、提交或推送。

## Standards 后续：合成夹具的四段权限码

旧冻结候选 7e89c485 的 Spec 有界通过；Standards STD-01 指出测试期望值与注解使用三段权限码，不符合所有新增/变更的编码规范。当前仅把两处 `admin:demo:view` 同步改为 `admin:audit:demo:view`，不改继承断言或任何生产权限、持久化定义，因此不是生产权限重命名迁移。

测试的全部断言及两条脱敏回归保留；将这两处夹具文字逆映射后，再移除两新增方法并归一行结束符，可与原七测封存精确比较。该修正是静态规范跟进，不制造行为 RED；新源码、门禁及独立两轴需另行冻结，旧候选与历史收据不覆盖。
