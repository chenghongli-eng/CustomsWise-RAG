# 数据库迁移脚本

本目录存放 **一次性** 数据库 schema/数据迁移 SQL。项目没有引入 Flyway / Liquibase，脚本由 DBA 或开发者手动执行。

## 文件命名约定

```
V<YYYYMMDD>__<scope>_<description>.sql
```

- `V<YYYYMMDD>`：版本号 = 提交日期（与 git commit 日期对齐，方便追溯）
- `<scope>`：表名或模块名（如 `qa_history`）
- `<description>`：动作用 snake_case 短语

## 当前脚本清单

| 文件 | 作用 | 是否可重入 |
|------|------|-----------|
| `V20260831__qa_history_references_info_jsonb_to_text.sql` | qa_history.references_info JSONB → TEXT（commit 3e2e089 配套迁移） | 是（带类型检查 guard） |

## 运行方式

```bash
# 1. 检查当前列类型（预校验）
PGPASSWORD=customswise123 psql -h localhost -U customswise -d customswise_rag \
  -c "SELECT column_name, data_type FROM information_schema.columns \
      WHERE table_name='qa_history' AND column_name='references_info';"

# 2. 干跑（事务内执行，错了回滚）
PGPASSWORD=customswise123 psql -h localhost -U customswise -d customswise_rag \
  --single-transaction \
  -f src/main/resources/db/migration/V20260831__qa_history_references_info_jsonb_to_text.sql

# 3. 确认 NOTICE 输出符合预期
# 期望看到: "qa_history.references_info: jsonb -> text 已迁移完成"
# 或:       "qa_history.references_info: 当前类型非 jsonb，无需迁移（已为 TEXT 或已被迁移）"

# 4. 跑完后跑脚本末尾的 3 条校验 SQL
```

## 何时需要迁移

| 部署场景 | 是否需要 |
|----------|---------|
| 全新部署（schema.sql 首次执行） | ❌ 不需要，schema.sql 已直接用 TEXT |
| 老部署但 schema.sql 同步过、qa_history 已重建 | ❌ 不需要 |
| 老部署、qa_history 已存在 JSONB 数据 | ✅ 需要跑 |

判断方法：跑步骤 1 的检查查询，看 `data_type` 是 `text` 还是 `jsonb`。如果是 `jsonb`，跑迁移；如果是 `text`，跳过。

## 回滚（如需）

```sql
-- 仅在迁移后发现反序列化异常时回滚（极少见，因为 USING 子句 100% 兼容）
ALTER TABLE qa_history
    ALTER COLUMN references_info TYPE JSONB
    USING references_info::jsonb;
```

## 新增脚本流程

1. 命名遵循 `V<YYYYMMDD>__<scope>_<description>.sql`
2. 文件顶部加注释说明：背景 / 适用场景 / 风险 / 锁表时长
3. 用 `DO $$ ... $$` 包一层 idempotent 检查，避免重复执行炸掉
4. 末尾附 1-3 条手动校验 SQL（用 `--` 注释），不直接执行
5. 在本 README 的"当前脚本清单"表里登记
