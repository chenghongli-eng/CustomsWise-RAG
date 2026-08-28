-- CustomsWise RAG 数据库初始化脚本

-- 1. 政策文档表
CREATE TABLE IF NOT EXISTS policy_document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    document_number VARCHAR(100),
    publish_date DATE,
    effective_date DATE,
    expire_date DATE,
    status VARCHAR(20) DEFAULT '现行' CHECK (status IN ('现行', '已废止')),
    applicable_business VARCHAR(200),
    summary TEXT,
    file_path VARCHAR(500),
    file_hash VARCHAR(64),
    milvus_collection VARCHAR(100),
    milvus_doc_id VARCHAR(100),
    reference_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    deleted BOOLEAN DEFAULT FALSE
);

-- 2. 文档切片表
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT REFERENCES policy_document(id) ON DELETE CASCADE,
    chunk_index INT,
    content TEXT,
    chapter_path VARCHAR(200),
    vector_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 问答历史表
CREATE TABLE IF NOT EXISTS qa_history (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100),
    user_query TEXT NOT NULL,
    user_conditions TEXT,
    ai_response TEXT,
    references_info JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_policy_document_status ON policy_document(status);
CREATE INDEX IF NOT EXISTS idx_policy_document_business ON policy_document(applicable_business);
CREATE INDEX IF NOT EXISTS idx_policy_document_deleted ON policy_document(deleted);
CREATE INDEX IF NOT EXISTS idx_document_chunk_document_id ON document_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_qa_history_session_id ON qa_history(session_id);
CREATE INDEX IF NOT EXISTS idx_qa_history_created_at ON qa_history(created_at);



-- CustomsWise RAG 数据库初始化脚本 增加表、字段注释
-- 1.政策文档表注释
COMMENT ON TABLE policy_document IS '政策文档主表，存储PDF政策文件元数据信息';
COMMENT ON COLUMN policy_document.id IS '主键ID';
COMMENT ON COLUMN policy_document.title IS '政策文档标题';
COMMENT ON COLUMN policy_document.document_number IS '政策文号';
COMMENT ON COLUMN policy_document.publish_date IS '发布日期';
COMMENT ON COLUMN policy_document.effective_date IS '生效日期';
COMMENT ON COLUMN policy_document.expire_date IS '失效日期';
COMMENT ON COLUMN policy_document.status IS '文档状态：现行、已废止';
COMMENT ON COLUMN policy_document.applicable_business IS '适用业务场景';
COMMENT ON COLUMN policy_document.summary IS '文档摘要';
COMMENT ON COLUMN policy_document.file_path IS 'PDF文件存储路径(MinIO对象存储地址)';
COMMENT ON COLUMN policy_document.file_hash IS 'PDF文件SHA256哈希值，用于校验文件是否变更';
COMMENT ON COLUMN policy_document.milvus_collection IS 'Milvus向量集合名称';
COMMENT ON COLUMN policy_document.milvus_doc_id IS 'Milvus中文档维度ID，关联该文档全部向量分片';
COMMENT ON COLUMN policy_document.reference_count IS '被问答引用次数，统计用';
COMMENT ON COLUMN policy_document.created_at IS '创建时间';
COMMENT ON COLUMN policy_document.updated_at IS '更新时间';
COMMENT ON COLUMN policy_document.created_by IS '创建人';
COMMENT ON COLUMN policy_document.deleted IS '逻辑删除标记：false未删除，true已删除';

-- 2.文档切片表注释
COMMENT ON TABLE document_chunk IS '文档切片表，PDF解析后拆分的文本chunk，和Milvus向量一一对应';
COMMENT ON COLUMN document_shturl.c IS '主键ID';
COMMENT ON COLUMN document_chunk.document_id IS '关联policy_document政策文档ID';
COMMENT ON COLUMN document_chunk.chunk_index IS '分片序号，同一个文档内分片顺序编号';
COMMENT ON COLUMN document_chunk.content IS '分片原始文本内容';
COMMENT ON COLUMN document_chunk.chapter_path IS '章节路径，记录该分片所属标题层级，如：第一章>第一节';
COMMENT ON COLUMN document_chunk.vector_id IS 'Milvus向量主键ID，对应Milvus内部每条向量id';
COMMENT ON COLUMN document_chunk.created_at IS '分片创建时间';

--3.问答历史表注释
COMMENT ON TABLE qa_history IS 'RAG问答会话历史记录表';
COMMENT ON COLUMN qa_history.id IS '主键ID';
COMMENT ON COLUMN qa_history.session_id IS '会话ID，区分不同对话会话';
COMMENT ON COLUMN qa_history.user_query IS '用户提问内容';
COMMENT ON COLUMN qa_history.user_conditions IS '用户附加筛选条件（业务、时间等）';
COMMENT ON COLUMN qa_history.ai_response IS '大模型返回回答结果';
COMMENT ON COLUMN qa_history.references_info IS '检索引用来源信息，JSONB存储，记录召回的文档、切片信息';
COMMENT ON COLUMN qa_history.created_at IS '问答发生时间';


-- ============================================================================
-- 4. Schema 版本表（P0-数据迁移）
-- ============================================================================
CREATE TABLE IF NOT EXISTS schema_version (
    component VARCHAR(50) PRIMARY KEY,        -- 组件标识：milvus / pdf_extractor 等
    version INT NOT NULL,                      -- 当前 schema 版本号
    upgraded_at TIMESTAMP NOT NULL             -- 最近一次版本更新时间
);

COMMENT ON TABLE schema_version IS '组件 schema 版本记录表，用于追踪外部依赖（Milvus、PDF 解析器等）的 schema 演进状态';
COMMENT ON COLUMN schema_version.component IS '组件标识，如 milvus/pdf_extractor，PRIMARY KEY';
COMMENT ON COLUMN schema_version.version IS '当前已升级到的 schema 版本号';
COMMENT ON COLUMN schema_version.upgraded_at IS '本次版本变更时间';

-- 初始化 Milvus schema 版本
INSERT INTO schema_version (component, version, upgraded_at)
VALUES ('milvus', 1, CURRENT_TIMESTAMP)
ON CONFLICT (component) DO NOTHING;


-- ============================================================================
-- 5. 文档摄取任务表（P0-异步摄取）
-- ============================================================================
CREATE TABLE IF NOT EXISTS document_ingest_job (
    job_id VARCHAR(64) PRIMARY KEY,            -- UUID 任务标识
    document_id BIGINT REFERENCES policy_document(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    max_attempts INT NOT NULL DEFAULT 3,       -- 最大重试次数
    attempts INT NOT NULL DEFAULT 0,          -- 已尝试次数
    last_error TEXT,                           -- 最近一次失败堆栈（前 3 行）
    next_attempt_at TIMESTAMP,                 -- 下次允许重试时间（指数退避）
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP                      -- 任务结束时间（SUCCESS/FAILED）
);

-- 摄取任务常见查询索引
CREATE INDEX IF NOT EXISTS idx_ingest_job_status_next
    ON document_ingest_job(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_ingest_job_document_id
    ON document_ingest_job(document_id);

COMMENT ON TABLE document_ingest_job IS '文档摄取任务表，记录每个 PDF 文档解析+向量化的异步执行状态，支持失败重试与跨进程恢复';
COMMENT ON COLUMN document_ingest_job.job_id IS '任务 UUID，PRIMARY KEY';
COMMENT ON COLUMN document_ingest_job.document_id IS '关联 policy_document.id，外键';
COMMENT ON COLUMN document_ingest_job.status IS '任务状态：PENDING 待执行 / RUNNING 执行中 / SUCCESS 成功 / FAILED 失败（达 maxAttempts 后）';
COMMENT ON COLUMN document_ingest_job.max_attempts IS '最大重试次数，默认 3';
COMMENT ON COLUMN document_ingest_job.attempts IS '已执行次数（含失败），每次失败 +1';
COMMENT ON COLUMN document_ingest_job.last_error IS '最近一次失败的异常信息（前 3 行堆栈），用于排查';
COMMENT ON COLUMN document_ingest_job.next_attempt_at IS '下次允许调度的时间，失败重试时按指数退避（1min, 2min, 4min...）';
COMMENT ON COLUMN document_ingest_job.created_at IS '任务创建时间';
COMMENT ON COLUMN document_ingest_job.finished_at IS '任务结束时间（SUCCESS 或 FAILED 时设置）';


