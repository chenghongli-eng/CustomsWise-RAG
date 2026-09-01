-- =============================================================
-- V20260831__qa_history_references_info_jsonb_to_text.sql
--
-- 背景：
--   2026-08-31 (commit 3e2e089) 把 qa_history.references_info 从 JSONB
--   改为 TEXT。schema.sql 已对齐新类型（TEXT），但 **存量数据库** 中
--   旧列仍是 JSONB，且 Hibernate ddl-auto:update 不会主动 ALTER
--   列类型（特别是跨类型的破坏性变更），所以存量 DB 需要手动跑这
--   个脚本。
--
-- 适用场景：
--   - 部署时间在 2026-08-31 之前的 PostgreSQL 实例
--   - qa_history.references_info 当前类型为 jsonb
--
-- 行为：
--   - 已为 TEXT（全新安装或已迁移）：脚本 NOOP，仅打印 NOTICE
--   - 仍为 JSONB（存量）：ALTER COLUMN ... TYPE TEXT USING ...::text
--     将原 JSONB 值序列化为字符串存入新列
--
-- 风险：
--   - USING 子句对每行执行反序列化 → 转换；JSONB 历史上一定可序列化，
--     不存在数据丢失
--   - 锁表：ALTER COLUMN TYPE 会获取 ACCESS EXCLUSIVE 锁；
--     在大表上需要 schedule maintenance window（qa_history 通常
--     行数有限，无明显阻塞）
-- =============================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'qa_history'
          AND column_name  = 'references_info'
          AND data_type    = 'jsonb'
    ) THEN
        ALTER TABLE qa_history
            ALTER COLUMN references_info TYPE TEXT
            USING references_info::text;

        RAISE NOTICE 'qa_history.references_info: jsonb -> text 已迁移完成';
    ELSE
        RAISE NOTICE 'qa_history.references_info: 当前类型非 jsonb，无需迁移（已为 TEXT 或已被迁移）';
    END IF;
END $$;

-- =============================================================
-- 校验（手动执行，不在脚本里跑）
-- =============================================================

-- 1) 确认列类型
-- SELECT column_name, data_type
--   FROM information_schema.columns
--  WHERE table_name = 'qa_history' AND column_name = 'references_info';
-- 期望：data_type = 'text'

-- 2) 抽样 10 条确认 JSON 文本格式合法
-- SELECT id, length(references_info) AS len,
--        (references_info::jsonb IS NOT NULL) AS valid_json
--   FROM qa_history
--  WHERE references_info IS NOT NULL
--  ORDER BY id DESC
--  LIMIT 10;
-- 期望：valid_json 全为 true

-- 3) 总行数 + 非空率
-- SELECT count(*) AS total,
--        count(references_info) AS non_null,
--        count(*) FILTER (WHERE references_info IS NOT NULL) * 100.0 / count(*) AS non_null_pct
--   FROM qa_history;
