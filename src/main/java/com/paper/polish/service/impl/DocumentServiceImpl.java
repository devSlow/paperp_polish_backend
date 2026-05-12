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
import com.paper.polish.util.PptGenerateUtil;
import com.paper.polish.util.WordUtil;
import com.paper.polish.util.WordWriteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
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

        try {
        long totalStartTime = System.currentTimeMillis();
        String paperId = IdUtil.fastSimpleUUID();
        String fileName = file.getOriginalFilename();
        String originalPath = "original/" + paperId + "/" + fileName;
        log.info("========== 开始处理文档: {} ==========", fileName);

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
        final byte[] docxBytes = fileBytes;

        taskExecutor.execute(() -> {
            try {
                minioUtil.uploadToLocal(originalPath, new ByteArrayInputStream(docxBytes), file.getContentType());
                log.info("[1/4] 文件已缓存到本地");
            } catch (Exception e) {
                log.error("文件缓存失败: {}", e.getMessage());
            }
        });

        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setOriginalFilePath(originalPath);
        paper.setStatus("parsed");
        paper.setParagraphCount(0);
        paper.setRewrittenCount(0);
        paperService.save(paper);
        log.info("[2/4] 数据库记录 Paper 完成");

        long parseStartTime = System.currentTimeMillis();

        final WordUtil.ImageUploader imgCallback = (pid, imageName, data, contentType) -> {
            String imagePath = "images/" + pid + "/" + imageName;
            String url = minioUtil.getPresignedUrl(imagePath);
            minioUtil.uploadToLocal(imagePath, new ByteArrayInputStream(data), contentType);
            return url;
        };

        List<Paragraph> paragraphs;
        try (InputStream is = new ByteArrayInputStream(docxBytes)) {
            paragraphs = WordUtil.parseParagraphs(is, paperId, imgCallback);
        } catch (Exception e) {
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }

        log.info("[3/4] 解析文档内容完成 ({} 段落)，耗时: {}ms", paragraphs.size(), System.currentTimeMillis() - parseStartTime);
        log.info("[3/4-1] 图片已提交后台异步上传，不阻塞主流程");

        long dbBatchStartTime = System.currentTimeMillis();
        paragraphService.saveBatch(paragraphs, 1000);
        log.info("[DB] 批量插入 Paragraphs ({} 条) 完成，耗时: {}ms", paragraphs.size(), System.currentTimeMillis() - dbBatchStartTime);

        paper.setParagraphCount(paragraphs.size());
        paperService.updateById(paper);

        log.info("========== 文档处理完成，总耗时: {}ms ==========", System.currentTimeMillis() - totalStartTime);

        UploadResultDTO result = new UploadResultDTO();
        result.setPaperId(paperId);
        result.setStatus(paper.getStatus());
        result.setParagraphCount(paper.getParagraphCount());
        return result;
        } catch (Exception e) {
            log.warn("[上传] 处理失败，回退次数: deviceId={}", deviceId);
            dailyUsageService.rollback(deviceId);
            throw e;
        }
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

        try {
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
        } catch (Exception e) {
            if (round == 1) {
                log.warn("[润色] 失败，回退次数: deviceId={}", deviceId);
                dailyUsageService.rollback(deviceId);
            }
            throw e;
        }
    }

    @Override
    public RewriteResultDTO rewriteTextOnly(String text, String deviceId, int round) {
        log.info("[快速优化] 轮次: {}, deviceId={}, 调用前查询剩余次数", round, deviceId);
        int remaining = dailyUsageService.getRemaining(deviceId);
        log.info("[快速优化] 轮次: {}, deviceId={}, 当前剩余: {}", round, deviceId, remaining);
        if (remaining <= 0) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        if (round == 1) {
            boolean consumed = dailyUsageService.consume(deviceId);
            if (!consumed) {
                throw new RuntimeException("今日免费次数已用完，请先兑换");
            }
        }

        try {
        log.info("[快速优化] 轮次: {}, 输入长度: {} 字", round, text.length());
        String rewrittenText = callAiRewrite(text, round);
        log.info("[快速优化] 轮次: {}, 输出长度: {} 字", round, rewrittenText.length());

        RewriteResultDTO result = new RewriteResultDTO();
        result.setParagraphId("quick");
        result.setRewrittenText(rewrittenText);
        result.setRound(round);
        return result;
        } catch (Exception e) {
            if (round == 1) {
                log.warn("[快速优化] 失败，回退次数: deviceId={}", deviceId);
                dailyUsageService.rollback(deviceId);
            }
            throw e;
        }
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

    private static final String PROMPT_ROUND2 = "你的角色与目标：\n"
        + "你现在扮演一个专业的\"论文（或技术文档）修改助手\"。你的核心任务是接收一段中文原文（通常是技术性或学术性的描述），并将其改写成一种特定的风格。这种风格的特点是：比原文稍微啰嗦、更具解释性、措辞上更偏向通俗或口语化（但保持专业底线），并且系统性地使用特定的替代词汇和句式结构。\n"
        + "你的目标是精确地模仿分析得出的修改模式，生成\"修改后\"风格的文本，同时务必保持原文的核心技术信息、逻辑关系和事实准确性，也不要添加过多的字数。\n"
        + "注意不要过于口语化（通常情况下不会过于口语化，有一些比如至于xxx呢，这种的不要有）\n"
        + "注意！你输出的内容不应原多于原文！应时刻记得字数和原文相符！\n"
        + "注意！不要有''xxx呢''这种形式，如'至于vue呢'\n"
        + "不要第一人称\n\n"
        + "核心修改手法与规则（请严格遵守）：\n\n"
        + "增加冗余与解释性（Verbose Elaboration）：\n"
        + "动词短语扩展：将简洁的动词或动词短语替换为更长的、带有动作过程描述的短语。\n"
        + "示例：\"管理\" -> \"开展...的管理工作\" 或 \"进行管理\"\n"
        + "示例：\"交互\" -> \"进行交互\" 或 \"开展交互\"\n"
        + "示例：\"配置\" -> \"进行配置\"\n"
        + "示例：\"处理\" -> \"去处理...工作\"\n"
        + "示例：\"恢复\" -> \"进行恢复\"\n"
        + "示例：\"实现\" -> \"得以实现\" 或 \"来实现\"\n"
        + "增加辅助词/结构：在句子中添加语法上允许但非必需的词语，使句子更饱满。\n"
        + "示例：适当增加 \"了\"、\"的\"、\"地\"、\"所\"、\"会\"、\"可以\"、\"这个\"、\"方面\"、\"当中\" 等。\n"
        + "示例：\"提供功能\" -> \"有...功能\" 或 \"拥有...功能\"\n\n"
        + "系统性词汇替换（Systematic Synonym/Phrasing Substitution）：\n"
        + "特定动词/介词/连词替换：\n"
        + "采用 / 使用 -> 运用 / 选用 / 把...当作...来使用\n"
        + "基于 -> 鉴于 / 基于...来开展\n"
        + "利用 -> 借助 / 运用 / 凭借\n"
        + "通过 -> 借助 / 依靠 / 凭借\n"
        + "和 / 及 / 与 -> 以及（尤其是在列举多项时）\n"
        + "并 -> 并且 / 还 / 同时\n"
        + "其 -> 它 / 其（可根据语境选择，有时用\"它\"更口语化）\n"
        + "特定名词/形容词替换：\n"
        + "原因 -> 缘由 / 主要原因囊括...\n"
        + "符合 -> 契合\n"
        + "适合 -> 适宜\n"
        + "特点 -> 特性\n"
        + "极大(地) -> 极大程度(上)\n"
        + "立即 -> 马上\n\n"
        + "括号内容处理（Bracket Content Integration/Removal）：\n"
        + "解释性括号：对于原文中用于解释、举例或说明缩写的括号 (...) 或 （...）：\n"
        + "优先整合：尝试将括号内的信息自然地融入句子，使用 \"也就是\"、\"即\"、\"比如\"、\"像\" 等引导词。\n"
        + "示例：ORM（对象关系映射） -> 对象关系映射即ORM 或 ORM也就是对象关系映射\n"
        + "示例：功能（如ORM、Admin） -> 功能，比如ORM、Admin 或 功能，像ORM、Admin等\n"
        + "谨慎省略：如果整合后语句极其冗长或别扭，并且括号内容并非核心关键信息，可以考虑省略。但要极其小心，避免丢失重要上下文或示例。\n"
        + "代码/标识符旁括号：对于紧跟在代码、文件名、类名旁的括号，通常直接移除括号。\n"
        + "示例：视图 (views.py) 中 -> 视图也就是views.py中\n\n"
        + "句式微调与口语化倾向（Sentence Structure & Colloquial Touch）：\n"
        + "使用\"把\"字句：在合适的场景下，倾向于使用\"把\"字句。\n"
        + "示例：\"会将对象移动\" -> \"会把对象移动\"\n"
        + "条件句式转换：将较书面的条件句式改为稍口语化的形式。\n"
        + "示例：\"若...，则...\" -> \"要是...，那就...\" 或 \"如果...，就...\"\n"
        + "名词化与动词化转换：根据需要进行调整，有时将名词性结构展开为动词性结构，反之亦然。\n"
        + "示例：\"为了将...解耦\" -> \"为了实现...的解耦\"\n"
        + "增加语气词/连接词：如在句首或句中添加\"那么\"、\"这样\"、\"同时\"等。\n\n"
        + "保持技术准确性（Maintain Technical Accuracy）：\n"
        + "绝对禁止修改：所有的技术术语、代码片段、库名、配置项、API路径等必须保持原样，不得修改或错误转写。\n"
        + "核心逻辑不变：修改后的句子必须表达与原文完全相同的技术逻辑、因果关系和功能描述。\n\n"
        + "硬性约束：\n"
        + "- 只输出改写后的正文，不得附加任何说明、解释或候选版本\n"
        + "- 输出字数控制在原文±10%范围内\n"
        + "- 不得改变原文核心意思、事实、论点和结论\n"
        + "- 不要第一人称，不要过于口语化（如\"至于xxx呢\"）\n"
        + "- 保持原有段落结构和编号";

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
        return callAi(systemPrompt, userContent, temperature, retryCount, null);
    }

    private String callAi(String systemPrompt, String userContent, double temperature, int retryCount, Integer maxTokens) {
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
            if (maxTokens != null && maxTokens > 0) {
                bodyMap.put("max_tokens", maxTokens);
                log.info("max_tokens: {}", maxTokens);
            }
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
            return callAiStream(systemPrompt, userContent, temperature, retryCount, maxTokens);
        } catch (RuntimeException e) {
            log.error("=== AI 调用异常 (RuntimeException) ===: {}", e.getMessage());
            // 如果内容为空且未超过重试次数，提高 temperature 重试
            if (retryCount < 2 && e.getMessage().contains("内容为空")) {
                double newTemp = Math.min(temperature + 0.2 * (retryCount + 1), 1.0);
                log.info("内容为空，提高 temperature 至 {} 重试 (第{}次)", newTemp, retryCount + 1);
                return callAi(systemPrompt, userContent, newTemp, retryCount + 1, maxTokens);
            }
            throw e;
        } catch (Exception e) {
            log.error("=== AI 调用异常 ===", e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    private String callAiStream(String systemPrompt, String userContent, double temperature, int retryCount) {
        return callAiStream(systemPrompt, userContent, temperature, retryCount, null);
    }

    private String callAiStream(String systemPrompt, String userContent, double temperature, int retryCount, Integer maxTokens) {
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
            if (maxTokens != null && maxTokens > 0) {
                bodyMap.put("max_tokens", maxTokens);
                log.info("max_tokens: {}", maxTokens);
            }
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
                    return callAi(systemPrompt, userContent, newTemp, retryCount + 1, maxTokens);
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
                return callAi(systemPrompt, userContent, newTemp, retryCount + 1, maxTokens);
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

    @Override
    public PptGenerateResultDTO generatePpt(String paperId, String deviceId, HttpServletRequest httpRequest) {
        int remaining = dailyUsageService.getRemaining(deviceId);
        if (remaining <= 0) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        boolean consumed = dailyUsageService.consume(deviceId);
        if (!consumed) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        try {
            return doGeneratePpt(paperId, deviceId, httpRequest);
        } catch (Exception e) {
            log.warn("[PPT生成] 生成失败或客户端断开，回退次数: deviceId={}", deviceId);
            dailyUsageService.rollback(deviceId);
            throw e;
        }
    }

    private void checkClientConnected(HttpServletRequest request) {
        try {
            if (request.getInputStream().available() >= 0) {}
        } catch (java.io.IOException e) {
            throw new RuntimeException("客户端已断开连接");
        }
    }

    private PptGenerateResultDTO doGeneratePpt(String paperId, String deviceId, HttpServletRequest httpRequest) {
        Paper paper = paperService.getById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("论文不存在: " + paperId);
        }

        List<Paragraph> paragraphs = paragraphService.list(new LambdaQueryWrapper<Paragraph>()
                .eq(Paragraph::getPaperId, paperId)
                .orderByAsc(Paragraph::getParagraphIndex));

        StringBuilder sb = new StringBuilder();
        for (Paragraph p : paragraphs) {
            String text = p.getCurrentText() != null ? p.getCurrentText() : p.getOriginalText();
            if (text != null && !text.isEmpty() && p.getCanRewrite() != null && p.getCanRewrite()) {
                sb.append(text).append("\n\n");
            }
        }

        if (sb.length() == 0) {
            throw new RuntimeException("论文中无可用的正文内容用于生成 PPT");
        }

        String paperText = sb.toString();
        String fileName = new File(paper.getOriginalFilePath()).getName();
        log.info("[PPT生成-SVG] 论文ID: {}, 正文长度: {} 字, 文件名: {}", paperId, paperText.length(), fileName);

        log.info("[PPT生成-SVG] 正在提取论文要点...");
        checkClientConnected(httpRequest);
        String outline = extractPaperOutline(paperText, fileName);
        log.info("[PPT生成-SVG] 提取完成，要点长度: {} 字", outline.length());

        checkClientConnected(httpRequest);
        List<String> svgSlides = generatePptPagesFromOutline(outline, fileName);
        if (svgSlides.isEmpty()) {
            throw new RuntimeException("PPT 生成失败：AI 未返回有效的 SVG 幻灯片");
        }

        log.info("[PPT生成-SVG] AI 生成完成，共 {} 页 SVG 幻灯片", svgSlides.size());

        byte[] pptxBytes;
        try {
            pptxBytes = com.paper.polish.util.SvgPptxConverter.convertToPptx(svgSlides, fileName);
        } catch (Exception e) {
            log.error("[PPT生成-SVG] SVG 转 PPTX 失败: {}", e.getMessage(), e);
            throw new RuntimeException("PPT 生成失败: " + e.getMessage(), e);
        }

        String pptxPath = "pptx/" + paperId + "/" + System.currentTimeMillis() + ".pptx";
        try {
            minioUtil.upload(pptxPath, new java.io.ByteArrayInputStream(pptxBytes),
                    pptxBytes.length, "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        } catch (Exception e) {
            throw new RuntimeException("PPT 上传 MinIO 失败: " + e.getMessage(), e);
        }

        String downloadUrl = minioUtil.getPresignedUrl(pptxPath);

        PptGenerateResultDTO result = new PptGenerateResultDTO();
        result.setPaperId(paperId);
        result.setDownloadUrl(downloadUrl);
        result.setSlideCount(svgSlides.size());

        log.info("[PPT生成-SVG] 完成，共 {} 页，下载链接有效期 24 小时", svgSlides.size());
        return result;
    }

    private static final String PROMPT_PPT_SVG = "你是一个专业的 AI 演示文稿设计师。请将以下论文内容转化为一份精美的学术 PPT，输出为 SVG 格式的幻灯片。\n\n"
            + "=== 技术规范 ===\n"
            + "画布尺寸：1280x720（PPT 16:9 宽屏）\n"
            + "每个幻灯片必须是一个完整的 <svg> 元素，包含 viewBox=\"0 0 1280 720\"\n"
            + "每个幻灯片前后用 <!-- SLIDE_START --> 和 <!-- SLIDE_END --> 包裹\n\n"
            + "=== 允许的 SVG 元素 ===\n"
            + "- <svg> 根元素（必须含 viewBox）\n"
            + "- <rect> 矩形（可用 rx/ry 做圆角）\n"
            + "- <ellipse> 椭圆（含 cx,cy,rx,ry）\n"
            + "- <circle> 圆形（含 cx,cy,r）\n"
            + "- <line> 线条（含 x1,y1,x2,y2）\n"
            + "- <polygon> 多边形（含 points）\n"
            + "- <path> 路径（仅用 M,L,Z,H,V 命令）\n"
            + "- <text> 文本（含 x,y,font-size,font-family,fill,font-weight）\n"
            + "- <tspan> 行内文本样式（可用 dy 换行）\n"
            + "- <g> 元素分组（用 id 命名，如 id=\"header\", id=\"card-1\"）\n"
            + "- <defs> 定义区（渐变、阴影）\n"
            + "- transform=\"translate(x,y) scale(s) rotate(deg)\"\n\n"
            + "=== 严格禁止 ===\n"
            + "- <style> 标签 / class 属性 / 外部 CSS\n"
            + "- <mask> / <filter> / <animate> / <script>\n"
            + "- <foreignObject> / <symbol> / <use>\n"
            + "- rgba() 颜色（改用 fill=\"#HEX\" + fill-opacity=\"0.x\"）\n"
            + "- 组 opacity（改为每个子元素单独设 fill-opacity）\n"
            + "- @font-face 自定义字体\n"
            + "- HTML 实体（&nbsp; 等，用 Unicode 字符本身）\n\n"
            + "=== 设计规则 ===\n"
            + "1. 配色方案：学术蓝色调 — 主色 #1E3A5F，强调色 #2E75B6，正文 #333333，背景 #F8FAFC\n"
            + "2. 字体：标题用 \"Microsoft YaHei\" 加粗，正文用 \"Microsoft YaHei\" 或 \"SimSun\"\n"
            + "3. 封面：深色渐变背景 + 白色大标题 + 副标题 + 装饰元素\n"
            + "4. 内容页：浅色背景 + 标题栏 + 内容卡片 + 图标装饰\n"
            + "5. 要点页：每条要点前加彩色圆点或小图标\n"
            + "6. 数据页：用简单柱状图或饼图展示数据（<rect> 或 <path> 绘制）\n"
            + "7. 结尾页：与封面风格一致的深色背景 + \"谢谢观看\"\n"
            + "8. 字号规范：封面标题36，副标题20，内容页标题28，正文24，行间距为字号的2倍\n"
            + "9. 标题文字单行显示，避免换行导致重叠\n"
            + "10. 文字定位：圆形内文字居中（text-anchor=\"middle\"），卡片内文字居中对齐\n"
            + "11. 图表标题在图表上方30px处，不遮挡图表内容\n"
            + "12. 【重要】文字元素必须放在背景rect之后，确保文字显示在最上层\n"
            + "13. 圆形内文字不要超过圆形范围，文字长度控制在直径以内\n\n"
            + "=== 幻灯片结构（20-25 页）===\n"
            + "第1页：封面（论文标题、作者、日期）\n"
            + "第2页：目录\n"
            + "第3-4页：研究背景与问题\n"
            + "第5-6页：研究意义\n"
            + "第7-8页：研究现状\n"
            + "第9-11页：研究方法\n"
            + "第12-15页：核心内容/实验/分析\n"
            + "第16-18页：结果与讨论\n"
            + "第19-20页：结论与展望\n"
            + "第21页：参考文献（选关键引用）\n"
            + "第22页：致谢\n"
            + "第23页：结尾（谢谢观看）\n"
            + "（可根据论文内容适当增减，保持 20-25 页）\n\n"
            + "=== 内容要求 ===\n"
            + "- 从论文中提取关键信息，每条要点简洁精炼（不超过 25 字）\n"
            + "- 保持学术论文的专业性和逻辑性\n"
            + "- 数据和结论必须忠实于原文\n"
            + "- 适当使用图表、图标增强可视化\n\n"
            + "=== 输出格式 ===\n"
            + "直接输出 SVG 代码，每页用注释分隔：\n"
            + "<!-- SLIDE_START -->\n"
            + "<svg viewBox=\"0 0 1280 720\" ...>...</svg>\n"
            + "<!-- SLIDE_END -->\n\n"
            + "不要输出任何解释文字，只输出 SVG 代码。";

    private static final String PROMPT_PPT_SVG_BATCH = "你是一个专业的 AI 演示文稿设计师。请继续生成学术 PPT 的第 {START_SLIDE} 到 第 {END_SLIDE} 页。\n\n"
            + "=== 技术规范 ===\n"
            + "画布尺寸：1280x720（PPT 16:9 宽屏）\n"
            + "每个幻灯片必须是一个完整的 <svg> 元素，包含 viewBox=\"0 0 1280 720\"\n"
            + "每个幻灯片前后用 <!-- SLIDE_START --> 和 <!-- SLIDE_END --> 包裹\n\n"
            + "=== 字号与间距规范（严格执行） ===\n"
            + "1. 主标题: font-size=\"36\"（标题不超过15个字，避免换行）\n"
            + "2. 副标题/卡片标题: font-size=\"28\"\n"
            + "3. 正文要点: font-size=\"24\"\n"
            + "4. 辅助说明/引用: font-size=\"20\"\n"
            + "5. 行间距：同一文本块内，相邻两行 <text> 的 y 坐标差值必须 >= 字体大小 * 2\n"
            + "6. 卡片间距：卡片之间垂直间距至少 50px，水平间距至少 40px\n"
            + "7. 绝对禁止使用小于 18 的字号！确保 PPT 在大屏幕上清晰可读\n"
            + "8. 避免重叠：任何两个元素的边界框（bounding box）不得相互重叠\n"
            + "9. 标题文字尽量单行显示！如果标题太长，缩短内容或缩小字号\n"
            + "10. 封面副标题（作者、日期等）使用小字号（18-20），确保不遮挡内容\n"
            + "=== 文字定位规范（严格执行） ===\n"
            + "1. 圆形/椭圆内文字：y坐标=圆心y坐标，text-anchor=\"middle\"，文字垂直居中\n"
            + "2. 矩形/卡片内文字：y坐标=矩形顶部y+矩形高度的一半+字号的一半，text-anchor=\"middle\"\n"
            + "3. 图表标题：放在图表上方，y坐标比图表顶部小30-40px，不遮挡图表\n"
            + "4. 文字必须完全在背景色块内部，不得溢出\n"
            + "5. 多行文字使用多个<text>元素，每行y坐标递增（递增量=字号*1.5）\n"
            + "6. 【重要】文字元素必须放在背景rect之后！确保文字显示在最上层不被遮挡\n"
            + "7. 圆形/椭圆内的文字长度不要超过直径，否则会溢出圆形范围\n"
            + "8. SVG元素顺序：先画背景，再画装饰，最后画文字（文字在最上层）\n"
            + "=== 允许的 SVG 元素 ===\n"
            + "- <rect>, <ellipse>, <circle>, <line>, <polygon>, <path> (仅 M,L,Z,H,V)\n"
            + "- <text>, <tspan>, <g>, <defs>, transform\n\n"
            + "=== 严格禁止 ===\n"
            + "- <style> / class / 外部 CSS / <mask> / <filter> / <animate> / <foreignObject>\n"
            + "- rgba() 颜色（改用 fill=\"#HEX\" + fill-opacity=\"0.x\"）\n\n"
            + "=== 设计规则 ===\n"
            + "1. 配色方案：学术蓝色调 — 主色 #1E3A5F，强调色 #2E75B6，正文 #333333，背景 #F8FAFC\n"
            + "2. 保持页面整洁，留白充足，重点突出\n"
            + "3. 内容页：浅色背景 + 标题栏 + 内容卡片 + 图标装饰\n"
            + "4. 结尾页 (第22页)：深色背景 + \"谢谢观看\"\n"
            + "5. 简化 SVG 路径，避免使用过于复杂的装饰性图形，以保持代码精简\n"
            + "6. 图表布局：图表标题在图表上方，间距30px；图表内容居中放置\n"
            + "7. 文字居中：使用text-anchor=\"middle\"，x坐标=背景块中心x\n\n"
            + "=== 风格参考 ===\n"
            + "{STYLE_REF}\n\n"
            + "=== 输出格式 ===\n"
            + "直接输出 SVG 代码，每页用注释分隔：\n"
            + "<!-- SLIDE_START -->\n"
            + "<svg viewBox=\"0 0 1280 720\" ...>...</svg>\n"
            + "<!-- SLIDE_END -->\n\n"
            + "不要输出任何解释文字，只输出 SVG 代码。";

    private static final String PROMPT_EXTRACT_OUTLINE = "你是一个专业的学术编辑。请阅读以下论文内容，提取核心要点，整理成一份用于制作 22 页学术 PPT 的结构化大纲。\n\n"
            + "=== 大纲要求 ===\n"
            + "1. 包含：研究背景、问题、意义、现状、方法、核心内容/实验、结果、结论等\n"
            + "2. 每一页标注清晰的标题和 3-4 条简要要点（每条不超过 20 字）\n"
            + "3. 输出为纯文本，层级分明，不要输出其他内容\n\n"
            + "论文内容：\n";

    private String extractPaperOutline(String text, String title) {
        String truncated;
        if (text.length() > 15000) {
            truncated = text.substring(0, 7500) + "\n\n...[中间内容省略]...\n\n" + text.substring(text.length() - 7500);
        } else {
            truncated = text;
        }
        try {
            return callAi(PROMPT_EXTRACT_OUTLINE, "论文标题：" + title + "\n\n" + truncated, 0.3);
        } catch (Exception e) {
            log.warn("[PPT生成-SVG] 提取要点失败，使用原文截断: {}", e.getMessage());
            return truncated;
        }
    }

    private List<String> generatePptPagesFromOutline(String outline, String title) {
        // 1. 优先生成封面（第 1 页），用于提取设计风格
        log.info("[PPT生成-SVG] 正在生成封面 (第 1 页) 以提取风格...");
        String coverSvg = "";
        try {
            coverSvg = callAiGenerateSvgBatchFromOutline(outline, title, 1, 1, "");
        } catch (Exception e) {
            log.error("封面生成失败: {}", e.getMessage());
        }

        List<String> slides = parseSvgSlides(coverSvg);
        String styleRef = "";
        if (!slides.isEmpty()) {
            // 提取前 1000 字符作为风格参考（通常包含 <defs> 和背景色）
            styleRef = slides.get(0).substring(0, Math.min(1000, slides.get(0).length()));
            log.info("[PPT生成-SVG] 封面生成成功，提取风格参考");
        } else {
            log.warn("[PPT生成-SVG] 封面生成失败或为空，后续页面将使用默认风格");
        }

        // 2. 并行生成剩余页面
        Map<Integer, List<String>> batchMap = new ConcurrentHashMap<>();
        int targetSlides = 22; // 测试模式：只生成1页
        int batchSize = 6; // 增大批次，减少请求次数
        int currentSlide = 2;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        while (currentSlide <= targetSlides) {
            int endSlide = Math.min(currentSlide + batchSize - 1, targetSlides);
            final int start = currentSlide;
            final int end = endSlide;
            final String ref = styleRef;

            // 使用线程池并行执行
            futures.add(CompletableFuture.runAsync(() -> {
                log.info("[PPT生成-SVG] 开始并行生成第 {}-{} 页", start, end);
                boolean success = false;
                int retries = 0;
                while (!success && retries < 3) { // 增加重试机制应对 504
                    try {
                        String svg = callAiGenerateSvgBatchFromOutline(outline, title, start, end, ref);
                        List<String> parsed = parseSvgSlides(svg);
                        if (!parsed.isEmpty()) {
                            batchMap.put(start, parsed);
                            log.info("[PPT生成-SVG] 并行批次 {}-{} 成功，获取 {} 页", start, end, parsed.size());
                            success = true;
                        } else {
                            log.warn("[PPT生成-SVG] 并行批次 {}-{} 解析为空，重试...", start, end);
                            retries++;
                        }
                    } catch (Exception e) {
                        log.error("[PPT生成-SVG] 并行批次 {}-{} 异常: {}", start, end, e.getMessage());
                        retries++;
                    }
                }
            }, taskExecutor));

            currentSlide = endSlide + 1;
        }

        // 等待所有并行任务完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("[PPT生成-SVG] 等待并行任务超时或异常: {}", e.getMessage());
        }

        // 按顺序合并结果
        List<String> finalSlides = new ArrayList<>(slides); // 包含封面
        List<Integer> keys = new ArrayList<>(batchMap.keySet());
        Collections.sort(keys);

        for (Integer key : keys) {
            finalSlides.addAll(batchMap.get(key));
        }

        log.info("[PPT生成-SVG] 全部页面生成完成，共 {} 页", finalSlides.size());
        return finalSlides;
    }

    private String callAiGenerateSvgBatchFromOutline(String outline, String title, int startSlide, int endSlide, String styleRef) {
        String prompt = PROMPT_PPT_SVG_BATCH
                .replace("{START_SLIDE}", String.valueOf(startSlide))
                .replace("{END_SLIDE}", String.valueOf(endSlide))
                .replace("{STYLE_REF}", styleRef.isEmpty() ? "（无，请根据论文内容自由设计）" : "参考上一页风格：\n" + styleRef.substring(0, Math.min(800, styleRef.length())) + "...");

        String userContent = "论文标题：" + title + "\n\n论文结构化大纲：\n" + outline;
        // PPT SVG 生成需要更高的 token 限制，避免截断
        return callAi(prompt, userContent, 0.4, 0, 16000);
    }

    private List<String> parseSvgSlides(String svgOutput) {
        List<String> slides = new java.util.ArrayList<>();
        log.info("[PPT生成-SVG] parseSvgSlides 原始内容长度: {}, 前500字: {}", svgOutput.length(), svgOutput.substring(0, Math.min(500, svgOutput.length())));

        svgOutput = svgOutput.replaceAll("```xml\\s*", "").replaceAll("```svg\\s*", "").replaceAll("```\\s*", "").trim();

        String[] blocks = svgOutput.split("(?=<!--\\s*SLIDE_START\\s*-->)");
        log.info("[PPT生成-SVG] split后 blocks 数量: {}", blocks.length);
        for (int i = 0; i < blocks.length; i++) {
            String block = blocks[i];
            log.info("[PPT生成-SVG] block[{}] 长度: {}, 内容前200字: {}", i, block.length(), block.substring(0, Math.min(200, block.length())));
            int start = block.indexOf("<!-- SLIDE_START -->");
            int end = block.indexOf("<!-- SLIDE_END -->", start + 1);
            log.info("[PPT生成-SVG] block[{}] start={}, end={}", i, start, end);
            if (start == -1) {
                int svgStart = block.indexOf("<svg");
                if (svgStart == -1) continue;
                int svgEnd = block.indexOf("</svg>", svgStart);
                log.info("[PPT生成-SVG] block[{}] svgStart={}, svgEnd={}", i, svgStart, svgEnd);
                if (svgEnd == -1) {
                    // SVG 未闭合，尝试修复
                    String incomplete = block.substring(svgStart);
                    log.info("[PPT生成-SVG] block[{}] 调用fixIncompleteSvg前 incomplete长度: {}", i, incomplete.length());
                    String fixed = fixIncompleteSvg(incomplete);
                    if (fixed != null) slides.add(fixed);
                    continue;
                }
                slides.add(block.substring(svgStart, svgEnd + 6).trim());
                continue;
            }
            if (end == -1) end = block.length();

            String content = block.substring(start, end).trim();
            int svgStart = content.indexOf("<svg");
            int svgEnd = content.indexOf("</svg>");
            if (svgStart != -1 && svgEnd != -1) {
                slides.add(content.substring(svgStart, svgEnd + 6).trim());
            } else if (svgStart != -1) {
                // SVG 未闭合，尝试修复
                String incomplete = content.substring(svgStart);
                String fixed = fixIncompleteSvg(incomplete);
                if (fixed != null) slides.add(fixed);
            }
        }

        if (slides.isEmpty()) {
            String[] candidates = svgOutput.split("(?=<!-)");
            for (String block : candidates) {
                if (!block.contains("<svg")) continue;
                int htmlStart = block.indexOf("<!-- SLIDE_START -->");
                int htmlEnd = block.indexOf("<!-- SLIDE_END -->");
                if (htmlStart != -1 && htmlEnd != -1) {
                    int svgStart = block.indexOf("<svg");
                    int svgEnd = block.indexOf("</svg>", svgStart);
                    if (svgStart != -1 && svgEnd != -1) {
                        slides.add(block.substring(svgStart, svgEnd + 6).trim());
                    } else if (svgStart != -1) {
                        String incomplete = block.substring(svgStart);
                        String fixed = fixIncompleteSvg(incomplete);
                        if (fixed != null) slides.add(fixed);
                    }
                } else {
                    int svgStart = block.indexOf("<svg");
                    while (svgStart != -1) {
                        int svgEnd = block.indexOf("</svg>", svgStart);
                        if (svgEnd == -1) {
                            String incomplete = block.substring(svgStart);
                            String fixed = fixIncompleteSvg(incomplete);
                            if (fixed != null) slides.add(fixed);
                            break;
                        }
                        slides.add(block.substring(svgStart, svgEnd + 6).trim());
                        svgStart = block.indexOf("<svg", svgEnd + 6);
                    }
                }
            }
        }

        if (slides.isEmpty()) {
            int svgStart = svgOutput.indexOf("<svg");
            while (svgStart != -1) {
                int svgEnd = svgOutput.indexOf("</svg>", svgStart);
                if (svgEnd == -1) {
                    String incomplete = svgOutput.substring(svgStart);
                    String fixed = fixIncompleteSvg(incomplete);
                    if (fixed != null) slides.add(fixed);
                    break;
                }
                slides.add(svgOutput.substring(svgStart, svgEnd + 6).trim());
                svgStart = svgOutput.indexOf("<svg", svgEnd + 6);
            }
        }

        log.info("[PPT生成-SVG] 解析到 {} 个 SVG 幻灯片", slides.size());
        return slides;
    }

    /**
     * 修复不完整的 SVG 代码
     * AI 响应可能被截断，导致 </svg> 缺失
     */
    private String fixIncompleteSvg(String svg) {
        if (svg == null || svg.isEmpty()) return null;

        log.info("[PPT生成-SVG] fixIncompleteSvg 收到内容长度: {}, 内容前300字: {}", svg.length(), svg.substring(0, Math.min(300, svg.length())));

        // 确保以 <svg 开头
        if (!svg.trim().startsWith("<svg")) return null;

        // 如果已经完整，直接返回
        if (svg.contains("</svg>")) return svg.trim();

        log.warn("[PPT生成-SVG] 检测到不完整的 SVG，尝试修复...");

        // 统计未闭合的标签
        java.util.Stack<String> tagStack = new java.util.Stack<>();
        java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*(/?)>");
        java.util.regex.Matcher matcher = tagPattern.matcher(svg);

        while (matcher.find()) {
            boolean isClosing = !matcher.group(1).isEmpty();
            boolean isSelfClosing = !matcher.group(3).isEmpty();
            String tagName = matcher.group(2).toLowerCase();

            // 跳过自闭合标签和特殊标签
            if (isSelfClosing || tagName.equals("defs") || tagName.equals("title") || tagName.equals("desc")) {
                continue;
            }

            if (isClosing) {
                if (!tagStack.isEmpty() && tagStack.peek().equals(tagName)) {
                    tagStack.pop();
                }
            } else {
                tagStack.push(tagName);
            }
        }

        // 补全未闭合的标签
        StringBuilder fixed = new StringBuilder(svg);
        while (!tagStack.isEmpty()) {
            String tag = tagStack.pop();
            fixed.append("</").append(tag).append(">");
            log.debug("[PPT生成-SVG] 补全标签: </{}>", tag);
        }

        // 确保有 </svg>
        if (!fixed.toString().contains("</svg>")) {
            fixed.append("</svg>");
            log.debug("[PPT生成-SVG] 补全 </svg>");
        }

        log.info("[PPT生成-SVG] SVG 修复完成, 修复后长度: {}", fixed.toString().trim().length());
        return fixed.toString().trim();
    }

}
