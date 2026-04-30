# PaperPolish Backend - 论文降重润色工具后端

## 技术栈
- Spring Boot 2.7.18 + MyBatis-Plus 3.5.5
- MySQL 5.7 + MinIO
- Apache POI（Word 解析/回写）
- OpenAI 兼容接口（AI 润色）

## 快速开始

### 1. 数据库初始化
```bash
mysql -u root -p < sql/paper_polish.sql
```

### 2. 配置
修改 `src/main/resources/application.yml`：
- MySQL 连接信息
- MinIO 连接信息
- OpenAI 接口配置（base-url / api-key / model）

### 3. 启动
```bash
mvn spring-boot:run
```

服务默认运行在 `http://localhost:8080`

## API 接口
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/document/upload` | POST | 上传 Word 文档 |
| `/api/document/{paperId}` | GET | 获取论文信息 |
| `/api/document/{paperId}/paragraphs` | GET | 获取段落列表 |
| `/api/document/{paperId}/paragraph/{id}/rewrite` | POST | 单段润色 |
| `/api/document/{paperId}/paragraph/{id}/accept` | POST | 确认替换 |
| `/api/document/{paperId}/paragraph/{id}/reject` | POST | 放弃润色 |
| `/api/document/{paperId}/export` | POST | 导出文档 |
