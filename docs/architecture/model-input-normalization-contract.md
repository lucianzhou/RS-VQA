# 模型输入规范化契约（question normalizer）

> 适用范围：`services/model-service` 的研究模型路径。
> 当前 normalizer 版本：`2.0.0`（`app/question_catalog.py:NORMALIZER_VERSION`）。

## 1. 为什么需要这层

冻结发布的分类器是 RSVQA-HR grouped closed-set ViLT，训练分布是**英文模板问题**。它对任意输入
文本都会强制输出 55 类答案之一，不会拒答。

实测（真实 CPU runtime，`data/test-images/single/san_francisco_coastal_urban.jpg`）：

| 输入 | 原始预测 | 置信度 | 预测题型 |
| --- | --- | ---: | --- |
| `有几条路？` | `no` | 0.6886 | count |
| `What is the amount of roads?` | `1` | 0.3905 | count |
| `图中有没有道路？`（旧实现直接传中文） | `no` | 0.4840 | count |
| `Is there a road?` | `yes` | 0.8523 | presence |

中文直传时答案形态与模型自己预测的题型互相矛盾（`count` 却回答 `no`）。因此**研究模型路径只接受
经过校验的 canonical question**，用户原文全程另行保存。

## 2. 边界

1. 只有研究模型 REAL/MOCK 路径使用 canonicalizer。外部通用视觉 Provider 接收用户原始问题，
   `QuestionUnderstanding.notApplicable(...)` 保证其 canonical 字段恒为空。
2. canonicalizer 只改写**输入文本**。checkpoint、logits、top-k 排序和原始 prediction 不被触碰。
3. 不引入答案类型 hard mask，不做 oracle / router / label mask。
4. 无法唯一映射时拒答或要求澄清，绝不猜测。

## 3. 数据结构

`app/question_catalog.py` 是纯数据，`app/question_matcher.py` 是匹配逻辑。

| 结构 | 作用 |
| --- | --- |
| `Intent` | `presence` / `count` / `area` / `comparison` 四类 |
| `GroundObject` | 地物：英文单复数、中文显示名、量词、几何类型、证据来源、别名、题型支持矩阵 |
| `SupportLevel` | `release_anchored` / `provisional` / `blocked` |
| `AmbiguousAlias` | 一词多义（如 `住宅`），触发澄清 |
| `PendingObject` | 识别得出但未核验的地物，指名拒答 |
| `BlockedTerm` | 明确不在词表内的地物（铁路、机场、桥梁） |
| `DecoyTerm` | 仅包含短别名的无关词（`思路`、`楼层`），避免误匹配 |
| `IntentSignal` | 题型触发词及其排除上下文（`几乎` 不算 `几`） |
| `CanonicalTemplate` | 实际送入模型的冻结句式及其证据来源 |

匹配采用**跨度最长优先**：先收集所有别名出现位置，长跨度先占位，因此
`residential building` 不会同时命中 `building`。

## 4. Canonical 模板与证据

| 题型 | Canonical 英文 | 证据 |
| --- | --- | --- |
| presence | `Is there a {singular}?` | 发布 wheel `rs_vqa/release_runtime.py` warmup 使用 `Is there a building?`；RSVQA-LR 问题集同句式 |
| count | `What is the amount of {plural}?` | `docs/16_predicted_soft_case_audit.md`（release evidence_reference）`What is the amount of roads?`；`thesis/experiment_materials.md` `What is the amount of buildings?`；release smoke `What is the amount of residential buildings in the image?` |
| area | `What is the area covered by {plural}?` | `docs/16_predicted_soft_case_audit.md` `What is the area covered by commercial buildings?` |
| comparison | `Are there more {plural} than {other_plural}?` | `docs/16_predicted_soft_case_audit.md` `Are there more roads than residential buildings?` |

**不变式**：canonical 输出必须是 normalizer 的不动点，即
`match(canonical(q)).canonical_question == canonical(q)`。由
`test_every_canonical_question_normalizes_to_itself` 固定。该测试在开发中确实抓到一个真实缺陷：
`Are there more A than B?` 曾被误判为 presence（`more than` 不是连续子串）。

## 5. 地物 × 题型支持矩阵

`release_anchored` 表示该组合在与本 release 绑定的 RSVQA-HR 证据中直接出现。

| 地物 | presence | count | area | comparison | 证据 |
| --- | --- | --- | --- | --- | --- |
| commercial building | anchored | anchored | anchored | provisional | hr_case_audit |
| residential building | provisional | anchored | anchored | anchored | hr_case_audit, release_smoke |
| building | anchored | anchored | provisional | provisional | release_warmup, hr_thesis_cases |
| road | provisional | anchored | **blocked** | anchored | hr_case_audit |
| park | provisional | provisional | provisional | anchored | hr_thesis_cases |
| parking | provisional | anchored | provisional | provisional | hr_case_audit |
| grass area | anchored | provisional | provisional | provisional | hr_thesis_cases |
| pedestrian | anchored | provisional | **blocked** | provisional | hr_case_audit |
| water area / farmland / forest / residential area | provisional | provisional | provisional | provisional | rsvqa_lr_questions |

### 已收紧的组合（原实现允许“任意对象 × 任意题型”）

1. **线状/点状地物的面积问题**（`road`、`pedestrian`）被拒答。冻结词表的面积答案是平方米区间，
   线状 OSM 要素没有多边形面积，且没有任何证据显示本 release 被问过此类问题。
2. **未核验地物**（学校、操场、施工区域、工业区）移出支持词表，改为指名拒答。它们在 RSVQA-HR
   与 RSVQA-LR 证据中均未出现，强行回答等于把未验证对象塞进 55 类词表。
3. **递减方向比较**（`Are there less A than B?`、`比…少`）被拒答。canonical 只表达 “more”，
   把 “less” 改写成 “more” 会反转语义，且在计数相等时并不等价。
4. **否定式存在问题**（`图中不存在道路吗？`）不匹配，要求改写，避免 yes/no 语义反转。

### 待核验项（不得当作已支持）

- `residential area`、`water area`、`farmland`、`forest` 仅有 RSVQA-LR 证据，HR 侧未确认。
- 除 `release_anchored` 外的所有组合均标记为 `provisional`，响应 `limitations` 中显式声明。
- RSVQA-HR 还存在形状/尺寸限定词（`rectangular buildings`、`small commercial buildings`）和
  `grass area` 等对象的更多题型，本版未纳入。
- 学校 / 操场 / 施工区域 / 工业区需要 RSVQA-HR 词表核验后才能恢复。

## 6. 响应契约

`PredictionResponse` 新增字段：

| 字段 | 含义 |
| --- | --- |
| `original_question` | 用户原文，逐字保留 |
| `canonical_question` | 校验后的英文 canonical 问题 |
| `canonical_question_display` | canonical 问题的中文呈现 |
| `model_input_question` | **实际送入研究运行时的文本** |
| `question_normalizer_version` | 产生该 canonical 的目录版本 |
| `matched_intent` / `matched_objects` | 识别到的题型与地物 key |
| `question_scope_verification` | `release_anchored` / `provisional` |
| `reason_code` | 稳定的机器可读结果码 |
| `needs_clarification` / `clarification_options` | 需要澄清时的候选项 |
| `interpretation_note` | UI 的“已理解为：…”提示 |
| `display_answer` / `display_locale` | 仅用于展示的本地化渲染 |
| `answer_shape_mismatch` | 原始答案与预测题型形态不符 |

澄清场景的 `status` 仍为 `unsupported`（确实没有产生答案），由 `needs_clarification` 承载区分。
这样 `answered + unsupported + failed` 的既有统计口径不会出现无归属的计数空洞。

## 7. 原始答案与展示答案

- 数据库 `model_invocation.answer`、`MessageEntity.content`、审计与报告一律存**原始预测**（`3`、`no`、`0m2`）。
- `display_answer` 仅是渲染（`3 条道路`）。前端 `.answer-value` 承载原始预测，`.answer-display`
  承载本地化渲染，二者不可互换。
- 若原始答案形态与预测题型不符（`count` 却返回 `no`），**不生成** display 文本，改为
  `answer_shape_mismatch=true` 并提示人工复核。绝不把 `no` 改写成 `3`。

## 8. Gateway 侧留痕

Flyway `V10__question_normalization_audit.sql` 为 `model_invocation` 增加 canonical question、
normalizer 版本、识别题型、地物、scope、reason code、display answer 等列。这些列**只**由
`ModelInvocationEntity.recordQuestionNormalization(...)` 写入，而外部 Provider 路径从不调用它，
因此“这条回答是否经过研究 canonicalizer”可以直接从数据库判定。

## 9. 变更规则

修改别名、模板、题型触发词或支持矩阵时，必须同时：

1. 更新本文档的证据列；
2. 递增 `NORMALIZER_VERSION`；
3. 保持 canonical 不动点测试通过；
4. 重新运行 `scripts/canonical_parity_smoke.py`（REAL runtime）。

模板变更会改变模型输入进而改变输出，属于研究口径变更，不是普通重构。
