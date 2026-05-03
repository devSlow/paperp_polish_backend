package com.paper.polish.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paper.polish.config.AiConfig;
import com.paper.polish.dto.*;
import com.paper.polish.entity.Paper;
import com.paper.polish.entity.Paragraph;
import com.paper.polish.service.DailyUsageService;
import com.paper.polish.service.DocumentService;
import com.paper.polish.service.PaperService;
import com.paper.polish.service.ParagraphService;
import com.paper.polish.util.MinioUtil;
import com.paper.polish.util.WordUtil;
import com.paper.polish.util.WordWriteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final PaperService paperService;
    private final ParagraphService paragraphService;
    private final MinioUtil minioUtil;
    private final AiConfig aiConfig;
    private final TaskExecutor taskExecutor;
    private final DailyUsageService dailyUsageService;

    @Override
    public UploadResultDTO upload(MultipartFile file, String deviceId) {
        int remaining = dailyUsageService.getRemaining(deviceId);
        if (remaining <= 0) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        boolean consumed = dailyUsageService.consume(deviceId);
        if (!consumed) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        long totalStartTime = System.currentTimeMillis();
        String paperId = IdUtil.fastSimpleUUID();
        String fileName = file.getOriginalFilename();
        String originalPath = "original/" + paperId + "/" + fileName;
        log.info("========== 开始处理文档: {} ==========", fileName);

        // 0. 读取文件字节（复用，避免反复上传下载）
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
        final byte[] docxBytes = fileBytes;

        // 1. 上传文件到 MinIO（异步，不阻塞主流程）
        taskExecutor.execute(() -> {
            try {
                minioUtil.upload(originalPath, new ByteArrayInputStream(docxBytes), docxBytes.length, file.getContentType());
                log.info("[1/4] 文件上传 MinIO 完成");
            } catch (Exception e) {
                log.error("文件上传 MinIO 失败: {}", e.getMessage());
            }
        });

        // 2. 插入 Paper 记录（同步）
        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setOriginalFilePath(originalPath);
        paper.setStatus("parsed");
        paper.setParagraphCount(0);
        paper.setRewrittenCount(0);
        paperService.save(paper);
        log.info("[2/4] 数据库记录 Paper 完成");

        // 3. 解析文档内容 + 图片上传 + 批量插入（同步，直接用内存字节流）
        long parseStartTime = System.currentTimeMillis();
        final long[] imageUploadTime = {0};
        try (InputStream is = new ByteArrayInputStream(docxBytes)) {
            List<Paragraph> paragraphs = WordUtil.parseParagraphs(is, paperId,
                    (pid, imageName, data, contentType) -> {
                        String imagePath = "images/" + pid + "/" + imageName;
                        long imgStart = System.currentTimeMillis();
                        try {
                            minioUtil.upload(imagePath, new ByteArrayInputStream(data), data.length, contentType);
                            imageUploadTime[0] += System.currentTimeMillis() - imgStart;
                            return minioUtil.getPresignedUrl(imagePath);
                        } catch (Exception e) {
                            log.error("图片上传失败: {}", e.getMessage());
                            return null;
                        }
                    });

            log.info("[3/4] 解析文档内容完成 ({} 段落)，耗时: {}ms", paragraphs.size(), System.currentTimeMillis() - parseStartTime);
            log.info("[3/4-1] 图片上传完成，耗时: {}ms", imageUploadTime[0]);

            // 批量插入段落
            long dbBatchStartTime = System.currentTimeMillis();
            paragraphService.saveBatch(paragraphs);
            log.info("[DB] 批量插入 Paragraphs ({} 条) 完成，耗时: {}ms", paragraphs.size(), System.currentTimeMillis() - dbBatchStartTime);

            paper.setParagraphCount(paragraphs.size());
            paperService.updateById(paper);
        } catch (Exception e) {
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }

        log.info("========== 文档处理完成，总耗时: {}ms ==========", System.currentTimeMillis() - totalStartTime);

        UploadResultDTO result = new UploadResultDTO();
        result.setPaperId(paperId);
        result.setStatus(paper.getStatus());
        result.setParagraphCount(paper.getParagraphCount());
        return result;
    }

    @Override
    public PaperInfoDTO getPaperInfo(String paperId) {
        Paper paper = paperService.getById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("论文不存在: " + paperId);
        }
        PaperInfoDTO dto = new PaperInfoDTO();
        BeanUtil.copyProperties(paper, dto);
        dto.setPaperId(paper.getId());
        return dto;
    }

    @Override
    public ParagraphListDTO getParagraphs(String paperId) {
        Paper paper = paperService.getById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("论文不存在: " + paperId);
        }

        List<Paragraph> paragraphs = paragraphService.listByPaperId(paperId);

        ParagraphListDTO dto = new ParagraphListDTO();
        dto.setPaperId(paperId);
        dto.setParagraphCount(paragraphs.size());
        dto.setParagraphs(paragraphs.stream().map(p -> {
            ParagraphListDTO.ParagraphItemDTO item = new ParagraphListDTO.ParagraphItemDTO();
            BeanUtil.copyProperties(p, item);
            return item;
        }).collect(Collectors.toList()));
        return dto;
    }

    @Override
    public RewriteResultDTO rewriteParagraph(String paperId, String paragraphId, String text, String deviceId, String selectedText) {
        return rewriteParagraph(paperId, paragraphId, text, deviceId, selectedText, 1);
    }

    @Override
    public RewriteResultDTO rewriteParagraph(String paperId, String paragraphId, String text, String deviceId, String selectedText, int round) {
        int remaining = dailyUsageService.getRemaining(deviceId);
        if (remaining <= 0) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        // 仅在第一轮消耗次数，两轮润色算一次
        if (round == 1) {
            boolean consumed = dailyUsageService.consume(deviceId);
            if (!consumed) {
                throw new RuntimeException("今日免费次数已用完，请先兑换");
            }
        }

        Paragraph paragraph = paragraphService.getById(paragraphId);
        if (paragraph == null || !paragraph.getPaperId().equals(paperId)) {
            throw new IllegalArgumentException("段落不存在");
        }
        if (!paragraph.getCanRewrite()) {
            throw new IllegalArgumentException("该段落不允许润色");
        }

        String sourceText;
        if (selectedText != null && !selectedText.isEmpty()) {
            sourceText = selectedText;
        } else if (text != null && !text.isEmpty()) {
            sourceText = text;
        } else {
            sourceText = paragraph.getOriginalText();
        }

        log.info("[润色] 段落ID: {}, 轮次: {}, 输入长度: {} 字, 来源: {}", 
                paragraphId, round, sourceText.length(), 
                selectedText != null ? "选区" : (text != null ? "参数" : "原文"));

        String rewrittenText = callAiRewrite(sourceText, round);

        log.info("[润色] 段落ID: {}, 轮次: {}, 输出长度: {} 字", paragraphId, round, rewrittenText.length());

        // 只有全文润色时才保存结果到数据库，选区润色仅返回结果由前端处理拼接
        if (selectedText == null || selectedText.isEmpty()) {
            paragraph.setRewrittenText(rewrittenText);
            paragraph.setStatus("rewritten");
            paragraphService.updateById(paragraph);
        }

        RewriteResultDTO result = new RewriteResultDTO();
        result.setParagraphId(paragraphId);
        result.setRewrittenText(rewrittenText);
        result.setRound(round);
        return result;
    }

    @Override
    public RewriteResultDTO rewriteTextOnly(String text, String deviceId, int round) {
        int remaining = dailyUsageService.getRemaining(deviceId);
        if (remaining <= 0) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        if (round == 1) {
            boolean consumed = dailyUsageService.consume(deviceId);
            if (!consumed) {
                throw new RuntimeException("今日免费次数已用完，请先兑换");
            }
        }

        log.info("[快速降重] 轮次: {}, 输入长度: {} 字", round, text.length());
        String rewrittenText = callAiRewrite(text, round);
        log.info("[快速降重] 轮次: {}, 输出长度: {} 字", round, rewrittenText.length());

        RewriteResultDTO result = new RewriteResultDTO();
        result.setParagraphId("quick");
        result.setRewrittenText(rewrittenText);
        result.setRound(round);
        return result;
    }

    @Override
    public void acceptParagraph(String paperId, String paragraphId, AcceptRequestDTO request) {
        Paragraph paragraph = paragraphService.getById(paragraphId);
        if (paragraph == null || !paragraph.getPaperId().equals(paperId)) {
            throw new IllegalArgumentException("段落不存在");
        }

        String newText;
        if (request != null && request.getText() != null) {
            newText = request.getText();
        } else if (paragraph.getRewrittenText() != null) {
            newText = paragraph.getRewrittenText();
        } else {
            throw new IllegalArgumentException("没有可采纳的润色结果");
        }

        paragraph.setCurrentText(newText);
        paragraph.setRewrittenText(null);
        paragraph.setStatus("replaced");
        paragraphService.updateById(paragraph);

        updateRewrittenCount(paperId);
    }

    public RewriteResultDTO.ScoreResult scoreParagraph(String paragraphId) {
        Paragraph paragraph = paragraphService.getById(paragraphId);
        if (paragraph == null || paragraph.getRewrittenText() == null) {
            return null;
        }
        String original = paragraph.getOriginalText() != null ? paragraph.getOriginalText() : "";
        return callAiScore(original, paragraph.getRewrittenText());
    }

    @Override
    public void rejectParagraph(String paperId, String paragraphId) {
        Paragraph paragraph = paragraphService.getById(paragraphId);
        if (paragraph == null || !paragraph.getPaperId().equals(paperId)) {
            throw new IllegalArgumentException("段落不存在");
        }

        paragraph.setRewrittenText(null);
        paragraph.setStatus("original");
        paragraphService.updateById(paragraph);

        updateRewrittenCount(paperId);
    }

    @Override
    public ExportResultDTO exportDocument(String paperId) {
        Paper paper = paperService.getById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("论文不存在: " + paperId);
        }

        List<Paragraph> paragraphs = paragraphService.list(new LambdaQueryWrapper<Paragraph>()
                .eq(Paragraph::getPaperId, paperId)
                .orderByAsc(Paragraph::getParagraphIndex));

        Path tempInput = null;
        Path tempOutput = null;
        try {
            tempInput = Files.createTempFile("input_", ".docx");
            tempOutput = Files.createTempFile("output_", ".docx");

            try (InputStream is = minioUtil.download(paper.getOriginalFilePath())) {
                Files.copy(is, tempInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            try (InputStream is = Files.newInputStream(tempInput);
                 OutputStream os = Files.newOutputStream(tempOutput)) {
                WordWriteUtil.rewriteDocument(is, os, paragraphs);
            }

            String exportPath = "export/" + paperId + "/" + System.currentTimeMillis() + ".docx";
            try (InputStream is = Files.newInputStream(tempOutput)) {
                minioUtil.upload(exportPath, is, Files.size(tempOutput),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            }

            paper.setLatestFilePath(exportPath);
            paper.setStatus("finished");
            paperService.updateById(paper);

            String downloadUrl = minioUtil.getPresignedUrl(exportPath);

            ExportResultDTO result = new ExportResultDTO();
            result.setDownloadUrl(downloadUrl);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("文档导出失败: " + e.getMessage(), e);
        } finally {
            try {
                if (tempInput != null) Files.deleteIfExists(tempInput);
                if (tempOutput != null) Files.deleteIfExists(tempOutput);
            } catch (Exception ignored) {
            }
        }
    }

    private static final String PROMPT_ROUND1 = "你现在扮演一个专业的论文修改助手。核心任务是将中文原文改写为更自然、更口语化的风格，同时保持专业底线。\n"
            + "修改手法：\n"
            + "1. 增加冗余与解释性：将简洁的动词短语替换为更长的描述性短语（如\"管理\"→\"开展管理工作\"），适当增加\"了、的、地、所\"等辅助词。\n"
            + "2. 系统性词汇替换：采用→运用、基于→鉴于、利用→借助、通过→凭借、和→以及、并→并且、符合→契合、特点→特性、极大→极大程度。\n"
            + "3. 括号内容处理：将括号内的解释信息用\"即\"\"也就是\"\"比如\"等自然融入句子。\n"
            + "4. 句式微调：适当使用\"把\"字句，将\"若…则…\"改为\"如果…就…\"，增加\"那么、这样、同时\"等连接词。\n"
            + "5. 保持技术准确性：所有术语、代码、配置项保持原样，核心逻辑不变。\n"
            + "硬性约束：\n"
            + "- 只输出改写后的正文，不得附加任何说明、解释或候选版本\n"
            + "- 输出字数控制在原文±10%范围内\n"
            + "- 不得改变原文核心意思、事实、论点和结论\n"
            + "- 不要第一人称，不要过于口语化（如\"至于xxx呢\"）\n"
            + "- 保持原有段落结构和编号";

    private static final String PROMPT_ROUND2 = "你是论文降AI率大师。任务是将文本从\"书面报告风格\"转变为\"专家讲解风格\"，显著降低AI检测率。\n"
            + "核心策略：\n"
            + "1. 词汇降维：将高度书面化的词汇替换为更日常的表达（内涵→包含的意义、演变→转变过程、将…定义为→把…界定成）。\n"
            + "2. 句法冗余化：打破简洁的主谓宾结构，增加修饰成分和结构助词，制造\"边想边说\"的语感（开创性研究→所开展的具有开创意义的研究成果）。\n"
            + "3. 逻辑连接松散化：将紧凑的逻辑连接词替换为更松散的关联词（然而→由于…所以、只要就→要是那么）。\n"
            + "4. 叙事节奏慢速化：通过增加词汇和复杂化句式，放慢信息传递速度。\n"
            + "5. 冗余中的精炼：构建复杂句式后反向审视，剔除真正的口水词和冗余助词，确保虽长但精、虽绕但顺。\n"
            + "硬性约束：\n"
            + "- 只输出改写后的正文，不附加任何说明、注解或候选版本\n"
            + "- 输出字数控制在原文±10%范围内\n"
            + "- 不得改变原文意思、事实、论点和逻辑关系\n"
            + "- 删除以\"综上所述\"\"总而言之\"开头的总结段落\n"
            + "- 保留数字编号格式和段落结构";

    private static final String PROMPT_SCORE = "你是一个文本质量评分专家。请对以下改写后的中文文本进行质量评分。\n"
            + "评分维度（每项1-10分）：\n"
            + "1. 直接性：直接陈述事实还是绕圈宣告？10=直截了当，1=充满铺垫\n"
            + "2. 节奏：句子长度是否变化？10=长短交错，1=机械重复\n"
            + "3. 信任度：是否尊重读者智慧？10=简洁明了，1=过度解释\n"
            + "4. 真实性：听起来像真人说话吗？10=自然流畅，1=机械生硬\n"
            + "5. 精炼度：还有可删减的内容吗？10=无冗余，1=大量废话\n"
            + "6. 语义保真：原文意思是否完整保留？10=完全保留，1=明显漂移\n"
            + "7. 纯净输出：是否彻底避免说明、建议、聊天腔？10=完全纯净，1=污染明显\n"
            + "必须严格按以下JSON格式输出，不要输出任何其他内容：\n"
            + "{\"directness\":X,\"rhythm\":X,\"trustworthiness\":X,\"authenticity\":X,\"conciseness\":X,\"semantic_fidelity\":X,\"purity\":X}\n"
            + "其中X为1-10的整数。";

    private String getSystemPrompt(int round) {
        if (round == 2) return PROMPT_ROUND2;
        return PROMPT_ROUND1;
    }

    private String callAiRewrite(String text, int round) {
        String systemPrompt = getSystemPrompt(round);
        String userContent = "原文（共" + text.length() + "字）：\n" + text;
        return callAi(systemPrompt, userContent, 0.7);
    }

    public RewriteResultDTO.ScoreResult callAiScore(String originalText, String rewrittenText) {
        String userContent = "原文：\n" + originalText + "\n\n改写后文本：\n" + rewrittenText;
        String scoreJson;
        try {
            scoreJson = callAi(PROMPT_SCORE, userContent, 0.3);
        } catch (Exception e) {
            log.warn("评分 AI 调用失败: {}", e.getMessage());
            return null;
        }

        try {
            // 清理 markdown 和代码块标记
            scoreJson = scoreJson.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            // 提取第一个 { ... } 块
            int start = scoreJson.indexOf("{");
            int end = scoreJson.lastIndexOf("}");
            if (start >= 0 && end > start) {
                scoreJson = scoreJson.substring(start, end + 1);
            } else {
                log.warn("评分响应不包含有效 JSON: {}", scoreJson);
                return null;
            }

            log.debug("评分 JSON: {}", scoreJson);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(scoreJson);

            RewriteResultDTO.ScoreResult score = new RewriteResultDTO.ScoreResult();
            score.setDirectness(node.path("directness").asInt());
            score.setRhythm(node.path("rhythm").asInt());
            score.setTrustworthiness(node.path("trustworthiness").asInt());
            score.setAuthenticity(node.path("authenticity").asInt());
            score.setConciseness(node.path("conciseness").asInt());
            score.setSemanticFidelity(node.path("semantic_fidelity").asInt());
            score.setPurity(node.path("purity").asInt());

            int total = score.getDirectness() + score.getRhythm() + score.getTrustworthiness()
                    + score.getAuthenticity() + score.getConciseness() + score.getSemanticFidelity() + score.getPurity();
            score.setTotal(total);

            if (total >= 56) score.setLevel("优秀");
            else if (total >= 42) score.setLevel("良好");
            else score.setLevel("需修订");

            log.info("评分结果: 总分 {}/70, 等级: {}", total, score.getLevel());
            return score;
        } catch (Exception e) {
            log.warn("评分解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String callAi(String systemPrompt, String userContent, double temperature) {
        return callAi(systemPrompt, userContent, temperature, 0);
    }

    private String callAi(String systemPrompt, String userContent, double temperature, int retryCount) {
        if (aiConfig.getBaseUrl() == null || aiConfig.getBaseUrl().isEmpty()
                || aiConfig.getApiKey() == null || aiConfig.getApiKey().isEmpty()) {
            throw new RuntimeException("AI 接口未配置，请在 application.yml 中配置 ai.openai 相关参数");
        }

        try {
            log.info("=== AI 调用开始 ===");
            log.info("模型: {}", aiConfig.getModel());
            log.info("输入文本长度: {} 字", userContent.length());
            log.info("输入文本 (前100字): {}", userContent.length() > 100 ? userContent.substring(0, 100) + "..." : userContent);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("model", aiConfig.getModel());
            bodyMap.put("temperature", temperature);
            bodyMap.put("stream", false);
            bodyMap.put("messages", java.util.List.of(
                    java.util.Map.of("role", "system", "content", systemPrompt),
                    java.util.Map.of("role", "user", "content", userContent)
            ));

            String body = mapper.writeValueAsString(bodyMap);
            String endpoint = aiConfig.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";

            log.info("请求地址: {}", endpoint);
            log.info("请求体长度: {} 字节", body.length());

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getApiKey())
                    .timeout(java.time.Duration.ofSeconds(180))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            // 先尝试非流式
            log.info("发送非流式请求...");
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            log.info("响应状态码: {}", response.statusCode());
            log.info("响应体长度: {} 字节", response.body().length());
            log.info("响应体 (前500字): {}", response.body().length() > 500 ? response.body().substring(0, 500) + "..." : response.body());

            if (response.statusCode() != 200) {
                log.error("AI 接口调用失败: HTTP {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("AI 接口调用失败(" + response.statusCode() + "): " + response.body());
            }

            String respBody = response.body();
            String result = parseAiResponse(respBody);
            if (result != null && !result.isEmpty()) {
                log.info("非流式解析成功，结果长度: {} 字", result.length());
                log.info("结果 (前100字): {}", result.length() > 100 ? result.substring(0, 100) + "..." : result);
                log.info("=== AI 调用结束 ===");
                return result;
            }

            // 如果非流式解析失败，说明 API 强制返回了流式，改用流式读取
            log.info("非流式解析失败，改用流式读取");
            return callAiStream(systemPrompt, userContent, temperature, retryCount);
        } catch (RuntimeException e) {
            log.error("=== AI 调用异常 (RuntimeException) ===: {}", e.getMessage());
            // 如果内容为空且未超过重试次数，提高 temperature 重试
            if (retryCount < 2 && e.getMessage().contains("内容为空")) {
                double newTemp = Math.min(temperature + 0.2 * (retryCount + 1), 1.0);
                log.info("内容为空，提高 temperature 至 {} 重试 (第{}次)", newTemp, retryCount + 1);
                return callAi(systemPrompt, userContent, newTemp, retryCount + 1);
            }
            throw e;
        } catch (Exception e) {
            log.error("=== AI 调用异常 ===", e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    private String callAiStream(String systemPrompt, String userContent, double temperature, int retryCount) {
        log.info("=== AI 流式调用开始 ===");
        log.info("模型: {}", aiConfig.getModel());
        log.info("输入文本长度: {} 字", userContent.length());
        log.info("请求地址: {}", aiConfig.getBaseUrl().replaceAll("/+$", "") + "/chat/completions");
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("model", aiConfig.getModel());
            bodyMap.put("temperature", temperature);
            bodyMap.put("stream", true);
            bodyMap.put("messages", java.util.List.of(
                    java.util.Map.of("role", "system", "content", systemPrompt),
                    java.util.Map.of("role", "user", "content", userContent)
            ));

            String body = mapper.writeValueAsString(bodyMap);
            String endpoint = aiConfig.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";

            log.info("发送流式请求...");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getApiKey())
                    .timeout(java.time.Duration.ofSeconds(180))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            StringBuilder content = new StringBuilder();
            java.util.concurrent.atomic.AtomicInteger lineCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.net.http.HttpResponse<java.util.stream.Stream<String>> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofLines());

            log.info("流式响应状态码: {}", response.statusCode());

            if (response.statusCode() != 200) {
                // 读取错误响应体
                StringBuilder errorBody = new StringBuilder();
                response.body().forEach(errorBody::append);
                log.error("流式调用失败: HTTP {} - {}", response.statusCode(), errorBody);
                throw new RuntimeException("AI 流式调用失败(" + response.statusCode() + "): " + errorBody);
            }

            response.body().forEach(line -> {
                lineCount.incrementAndGet();
                line = line.trim();
                if (!line.startsWith("data:")) return;
                String json = line.substring(5).trim();
                if ("[DONE]".equals(json) || json.isEmpty()) return;
                try {
                    com.fasterxml.jackson.databind.JsonNode chunk = mapper.readTree(json);
                    com.fasterxml.jackson.databind.JsonNode choices = chunk.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        String delta = choices.get(0).path("delta").path("content").asText(null);
                        if (delta != null) {
                            content.append(delta);
                            chunkCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析流式 chunk 失败: {}", e.getMessage());
                }
            });

            log.info("流式响应: 共 {} 行, {} 个有效 chunk, 内容长度: {} 字", lineCount.get(), chunkCount.get(), content.length());

            if (content.length() == 0) {
                log.error("流式响应内容为空");
                if (retryCount < 2) {
                    double newTemp = Math.min(temperature + 0.2 * (retryCount + 1), 1.0);
                    log.info("内容为空，提高 temperature 至 {} 重试 (第{}次)", newTemp, retryCount + 1);
                    return callAi(systemPrompt, userContent, newTemp, retryCount + 1);
                }
                throw new RuntimeException("AI 流式响应内容为空");
            }

            String cleaned = cleanAiContent(content.toString());
            log.info("流式解析成功，清理后长度: {} 字", cleaned.length());
            log.info("结果 (前100字): {}", cleaned.length() > 100 ? cleaned.substring(0, 100) + "..." : cleaned);
            log.info("=== AI 流式调用结束 ===");
            return cleaned;
        } catch (RuntimeException e) {
            log.error("=== AI 流式调用异常 (RuntimeException) ===: {}", e.getMessage());
            // 如果内容为空且未超过重试次数，提高 temperature 重试
            if (retryCount < 2 && e.getMessage().contains("内容为空")) {
                double newTemp = Math.min(temperature + 0.2 * (retryCount + 1), 1.0);
                log.info("内容为空，提高 temperature 至 {} 重试 (第{}次)", newTemp, retryCount + 1);
                return callAi(systemPrompt, userContent, newTemp, retryCount + 1);
            }
            throw e;
        } catch (Exception e) {
            log.error("=== AI 流式调用异常 ===", e);
            throw new RuntimeException("AI 流式调用失败: " + e.getMessage(), e);
        }
    }

    private String parseAiResponse(String responseBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            log.debug("AI 响应原文 (前500字): {}", responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);

            String trimmed = responseBody.trim();
            // 标准非流式响应
            if (trimmed.startsWith("{")) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(trimmed);
                
                // 检查是否为错误响应
                if (root.has("error")) {
                    String errMsg = root.path("error").path("message").asText("未知错误");
                    throw new RuntimeException("AI 接口返回错误: " + errMsg);
                }
                
                String content = root.path("choices").path(0).path("message").path("content").asText(null);
                if (content != null && !content.isEmpty()) {
                    return cleanAiContent(content);
                }
                log.warn("AI 返回空 content 字段");
                if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                    String c = root.get("choices").get(0).path("message").path("content").asText("");
                    if (!c.isEmpty()) return cleanAiContent(c);
                }
            }

            // SSE 流式响应
            StringBuilder content = new StringBuilder();
            for (String line : responseBody.split("\n")) {
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if ("[DONE]".equals(json) || json.isEmpty()) continue;
                try {
                    com.fasterxml.jackson.databind.JsonNode chunk = mapper.readTree(json);
                    String delta = chunk.path("choices").path(0).path("delta").path("content").asText(null);
                    if (delta != null) {
                        content.append(delta);
                    }
                } catch (Exception ignored) {
                }
            }

            if (content.length() > 0) {
                return cleanAiContent(content.toString());
            }

            log.warn("AI 响应无法解析，返回 null");
            return null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 AI 响应失败: " + e.getMessage(), e);
        }
    }

    private String cleanAiContent(String text) {
        if (text == null) return "";
        // 清除 Grok 推理标签
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        // 清除 markdown 粗体标记
        text = text.replaceAll("\\*\\*", "");
        // 清除前后空白
        return text.trim();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void updateRewrittenCount(String paperId) {
        long count = paragraphService.count(new LambdaQueryWrapper<Paragraph>()
                .eq(Paragraph::getPaperId, paperId)
                .eq(Paragraph::getStatus, "replaced"));
        Paper paper = paperService.getById(paperId);
        paper.setRewrittenCount((int) count);
        paperService.updateById(paper);
    }

}
