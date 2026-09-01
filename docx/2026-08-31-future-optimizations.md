# 2026-08-31 后续优化方向

按优先级排列，每项标注预估影响和实施成本。

> ✅ = 2026-08-31 已完成
> ✅9-1 = 2026-09-01 已完成
> 📋 = 已建立追踪文档，详见 [[业务问题追踪/2026-08-29-切片与状态粒度问题.md]]

---

## P0 — 立即可做（系统能力补全）

### 1. 候选不足时 LLM 改写 query
**问题**：空候选短路后返回固定文案，用户得自己换关键词，体验生硬。
**方案**：
- 短路时让 LLM 生成 3-5 个同义改写 query
- 逐个重新跑 embed + Milvus 检索
- 全部失败再返回固定文案
- 改写过程计入日志 `[FALLBACK] query_rewrite=true attempts=N`

**影响**：用户问得偏也能找到资料，召回率提升。
**成本**：中，主要是增加 LLM 调用（成本和时延）。

### 2. rerank 失败时告警 ✅9-1 已做
**问题**：当前 rerank 失败只 warn，无法触发告警。
**方案**：
- `RerankService` 失败时除了 warn，再发一个 `metrics.counter("rerank_fallback").inc()`
- Prometheus + AlertManager：`rerank_fallback_total{reason="api_returned_null"} > 10/min` 时告警

**影响**：及时发现 rerank 服务异常，避免长期静默降级。
**成本**：低，加 Micrometer 即可。

**已完成（2026-09-01）**：
- `pom.xml` 加 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
- `application.yml` 暴露 `/actuator/prometheus`，全局 tag `application`
- `RerankService` 注入 `MeterRegistry`，在 `api_returned_null` 和 catch-all 两处 fallback 埋点 counter（reason tag 区分）
- 指标名 `rag.rerank.fallback`，AlertManager 规则见 dev-log
- 待运维侧配置 Prometheus + AlertManager 后才能触发实际告警

### 3. PostgreSQL `references_info` JSONB → TEXT 历史数据迁移脚本 ✅9-1 已做
**问题**：今日 schema 变更改了字段类型，存量数据需要一次性迁移并校验。
**方案**：
- Flyway/Liquibase migration 用 `USING references_info::text` 兜底
- 跑完抽样校验反序列化是否正常
- 上线前在 staging 跑一遍完整数据

**影响**：避免线上数据迁移踩坑。
**成本**：极低，一次性脚本。

**已完成（2026-09-01）**：
- 新增 `src/main/resources/db/migration/V20260831__qa_history_references_info_jsonb_to_text.sql`（idempotent，DO $$ ... $$ 包类型检查 guard）
- 新增 `src/main/resources/db/migration/README.md`（命名约定 / 运行方式 / 适用场景 / 回滚）
- 脚本末尾附 3 条手动校验 SQL（类型确认 / 抽样 JSON 合法性 / 非空率）
- 项目无 Flyway/Liquibase，脚本由 DBA 手动执行

## P1 — 体验优化（短期 1-2 周）

### 4. 批量 Embedding
**问题**：上传 PDF 时一片一片向量化，串行慢。
**方案**：
- MiniMax Embedding API 支持 batch（body 传 `texts: [...]`），`embed()` 改为接受 `List<String>`
- 一次传 16-32 个 chunk，减少 HTTP 调用次数

**影响**：上传耗时减少 5-10x。
**成本**：低。

### 5. 引用片段精炼
**问题**：当前 reference.content 是 chunk 前 200 字符截断，可能截在关键条款中间。
**方案**：
- 检索阶段按"段落完整性"切分，不要在句子中间断
- 引用时找最相关的 1-2 句而不是前 200 字
- 或用 LLM 对 chunk 提取关键句

**影响**：用户看到的引用更精准。
**成本**：中。

### 6. 批量文档同一 session 上传后自动进入多轮问答
**问题**：用户上传一批文档后，希望针对这些文档连续追问，但当前每次问答独立。
**方案**：
- DocumentController 上传后返回一串 `documentIds`
- 前端带着这串 `documentIds` 和同一个 `sessionId` 发 QA 请求
- RAG 检索时支持限定 `document_id IN (...)` 只在这批上传文档中检索

**影响**：批量上传 + 追问场景体验闭环。
**成本**：中，Milvus 检索表达式加一层 docId IN filter。

### 7. BGE rerank candidate-cap 自动化 ✅9-1 已做
**问题**：今日硬编码 `candidate-cap=4` 是基于"4 条 × 500 字 已逼近 30s"的经验值，但不同文档长度应该可以动态调整。
**方案**：
- 在 `BGE_RERANK_PREP` 阶段根据 avgRaw 动态选 cap（avgRaw<200 → cap=6，200~400 → cap=4，>400 → cap=3）
- 保留 `rag.rerank.candidate-cap` 作为上限兜底

**影响**：短文档多召回、长文档少召回，整体质量/性能平衡。
**成本**：低，几行配置式代码。

**已完成（2026-09-01 下午，含参数调优）**：
- `application.yml` 新增 `rag.rerank.dynamic-cap` 配置段
- `RAGService.computeEffectiveCap(List<RagItem>)` 选档 + clamp 到 configuredCap
- 第 5b 步截断改用 effectiveCap，加 `[BGE_RERANK_CAP]` 日志
- 首版上线后跑了一条真实 query 发现 mid 档仍接近 30s timeout，做了二次调优：
  - `long-threshold-chars`: 400 → 300（让 mid 档 cap 也少 1 条）
  - `mid-cap`: 4 → 3，`long-cap`: 3 → 2
  - top-K 估算：`items.stream()` → `items.stream().limit(refK)`，refK = configuredCap（避免长 chunk 冒顶造成的全量低估）
- 改后预期：类似 case rerank 时间从 24.9s → ~12s
- 详见 [[2026-09-01-dev-log]] "参数调优"小节

## P2 — 检索质量优化（中期）

### 8. 混合检索（BM25 + 向量）✅ 已做
Milvus 2.5.6 原生 `BM25Function` 接入，提问时检索路径已叠加 BM25 与向量召回，milvus `parse` 字段单独存结构化文本兜底精确关键词召回。

### 9. rerank 入参与出参可观测性 ✅ 已做
BGE rerank 调用日志记录 candidates 数、分数分布、耗时，调参有数据支撑。今日额外补 `BGE_RERANK_PREP` 阶段的 `avgRaw/maxRaw/truncated` 字段。

### 10. 业务问题清单 📋 已建立追踪
详见 `业务问题追踪/2026-08-29-切片与状态粒度问题.md`，共 9 项：
1. 嵌套条款切片不完整
2. 条款引用文本被误切
3. Chunk 状态粒度为 Document 级别
4. 固定 chunkSize 破坏条款完整性
5. 文档编号评分二元化（0/1），无法识别引用正确但解读错误
6. 只看 top1 相似度，不反映整体检索质量
7. 三档免责声明不区分场景，无法指导用户正确行动
8. 文档级提取策略（整篇 PDFBox 或整篇 OCR），混合型 PDF 质量差
9. PDF 后清洗缺失 + 表格结构丢失

每项标注了状态、相关代码/文件、业务痛点、改造思路、预期结果。

## P3 — 高级能力（长期）

### 11. 多模态文档支持（扫描件 OCR）
**问题**：扫描件 PDF、图片需要 OCR；当前 PaddleOCR 兜底已有但依赖 Python 服务部署。
**方案**（已完成架构）：
- `PaddleOcrExtractor` (priority=15) 已接入，`PdfExtractorFactory` 策略模式自动降级
- 今日修了响应处理器二次读取 bug，OCR 实际识别成功率应该会显著上升

**待优化**：评估 PaddleOCR 识别准确率，若仍不够可考虑：
- 更高 DPI（当前 250）
- 图像预处理（去噪、二值化、倾斜校正）
- 商业 OCR API（腾讯云/阿里云 OCR）

### 12. 政策时间线视图
**问题**：用户想按时间维度浏览政策演变。
**方案**：PostgreSQL 已有 `publishDate`/`expireDate`，加专门查询接口，按时间轴组织政策。

**影响**：用户体验提升，便于了解政策历史。
**成本**：低-中。

### 13. 答案可信度评估 ✅ 已做
`AnswerCredibilityService` 基于 topSimilarity + citation 检查评估置信度，medium/low 等级追加免责声明。

### 14. A/B 测试框架 ✅ 已做
`ExperimentService` sessionId 哈希 sticky 分组，`QaHistory.experimentId` 记录供离线分析。今日落库到 qa_history 表。

## 立即推荐（成本-收益最佳）

| 优化项 | 预期收益 | 实施成本 | 状态 |
|--------|---------|---------|------|
| 候选不足时 query 改写（#1） | 中 | 中 | 待做 |
| rerank 失败告警（#2） | 中 | 极低 | ✅9-1 已做 |
| references_info 迁移脚本（#3） | 中 | 极低 | ✅9-1 已做 |
| 批量 Embedding（#4） | 中 | 低 | 待做 |
| 引用片段精炼（#5） | 中 | 中 | 待做 |
| 批量文档限定检索（#6） | 高 | 中 | 待做 |
| BGE cap 自动化（#7） | 中 | 低 | ✅9-1 已做 |
| 混合检索 BM25（#8） | 高 | 中 | ✅ 已做 |
| 政策时间线视图（#12） | 中 | 低-中 | 待做 |
| PaddleOCR 质量优化（#11） | 中 | 低 | 待做 |
| 业务问题清单（#10） | 高 | 高（多项） | 📋 追踪中 |
