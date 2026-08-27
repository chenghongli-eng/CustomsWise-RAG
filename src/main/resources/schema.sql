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
