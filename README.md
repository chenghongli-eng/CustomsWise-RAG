# CustomsWise RAG

跨境电商海关政策智能RAG助手

## 技术栈

- Java SpringBoot 3.x
- PostgreSQL 14+
- Milvus 2.3+ (向量数据库)
- MiniMax API (LLM + Embedding)

## 环境配置

### 1. JDK 21

```bash
# 检查版本
java -version
```

### 2. PostgreSQL 14+

**方式一：Docker部署**
```bash
docker run -d \
  --name customswise_pg \
  -e POSTGRES_USER=customswise \
  -e POSTGRES_PASSWORD=customswise123 \
  -e POSTGRES_DB=customswise_rag \
  -p 5432:5432 \
  -v postgres_data:/var/lib/postgresql/data \
  postgres:14
```

**方式二：本地安装**
```bash
sudo apt install postgresql-14
sudo -u postgres psql
CREATE USER customswise WITH PASSWORD 'customswise123';
CREATE DATABASE customswise_rag OWNER customswise;
\q
```

### 3. Milvus 2.3+

Milvus已默认安装并运行在：
- HTTP API: http://localhost:9091
- Milvus服务: localhost:19530

### 4. MiniMax API Key

注册 [MiniMax平台](https://www.minimaxi.com/) 获取API Key，配置到环境变量：
```bash
export MINIMAX_API_KEY=your_api_key_here
```

或在 `src/main/resources/application.yml` 中修改：
```yaml
minimax:
  api-key: your_api_key_here
```

## 快速启动

```bash
# 1. 克隆项目
git clone git@github.com:chenghongli-eng/CustomsWise-RAG.git
cd CustomsWise-RAG

# 2. 配置数据库连接（编辑application.yml或设置环境变量）

# 3. 编译运行
./mvnw spring-boot:run

# 或打包后运行
./mvnw package
java -jar target/customswise-rag-1.0.0.jar
```

## 项目结构

```
CustomsWise-RAG/
├── src/main/java/          # Java源代码
├── src/main/resources/     # 配置文件
│   └── application.yml
├── docx/                   # PRD文档
├── docker-compose.yml      # Docker配置
└── README.md
```

## 核心功能

1. **政策文档导入** - 上传PDF，系统解析、切片、向量化
2. **智能问答** - 基于RAG的政策问答，区分现行/废止政策
3. **知识库管理** - 管理政策文档，标记状态

## 免责声明

本系统仅作为参考工具，不构成报关法律依据。业务使用请以海关官方文件为准。
