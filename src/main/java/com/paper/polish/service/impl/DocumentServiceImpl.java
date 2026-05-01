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
import com.paper.polish.util.LibreOfficeUtil;
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
    private final LibreOfficeUtil libreOfficeUtil;
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
        String originalPath = "original/" + paperId + "/" + file.getOriginalFilename();
        log.info("========== 开始处理文档: {} ==========", file.getOriginalFilename());

        // 1. 上传文件到 MinIO（必须同步）
        long uploadStartTime = System.currentTimeMillis();
        try {
            minioUtil.upload(originalPath, file.getInputStream(), file.getSize(), file.getContentType());
            log.info("[1/4] 文件上传 MinIO 完成，耗时: {}ms", System.currentTimeMillis() - uploadStartTime);
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }

        // 2. 插入 Paper 记录（同步）
        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setOriginalFilePath(originalPath);
        paper.setStatus("parsed");
        paper.setParagraphCount(0);
        paper.setRewrittenCount(0);
        paperService.save(paper);
        log.info("[2/4] 数据库记录 Paper 完成");

        // 3. 解析文档内容 + 图片上传 + 批量插入（同步）
        long parseStartTime = System.currentTimeMillis();
        final long[] imageUploadTime = {0};
        try (InputStream is = minioUtil.download(originalPath)) {
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

        // 4. PDF 转换（异步，不阻塞主流程）
        String fileName = file.getOriginalFilename();
        taskExecutor.execute(() -> {
            long pdfStartTime = System.currentTimeMillis();
            try (InputStream is = minioUtil.download(originalPath)) {
                long docxDownloadStart = System.currentTimeMillis();
                byte[] docxBytes = is.readAllBytes();
                log.info("[4/4-1] 下载 DOCX 文件完成，耗时: {}ms", System.currentTimeMillis() - docxDownloadStart);

                long convertStart = System.currentTimeMillis();
                byte[] pdfBytes = libreOfficeUtil.convertToPdf(docxBytes, fileName);
                log.info("[4/4-2] LibreOffice PDF 转换完成，耗时: {}ms", System.currentTimeMillis() - convertStart);

                long pdfUploadStart = System.currentTimeMillis();
                String pdfPath = "pdf/" + paperId + "/" + fileName.replaceAll("\\.(?i)docx$", ".pdf");
                minioUtil.upload(pdfPath, new ByteArrayInputStream(pdfBytes), pdfBytes.length, "application/pdf");
                log.info("[4/4-3] 上传 PDF 到 MinIO 完成，耗时: {}ms", System.currentTimeMillis() - pdfUploadStart);

                paper.setPdfPath(pdfPath);
                paperService.updateById(paper);
                log.info("[4/4] PDF 转换与上传完成，总耗时: {}ms", System.currentTimeMillis() - pdfStartTime);
            } catch (Exception e) {
                log.warn("PDF 转换失败，不影响主流程: {}", e.getMessage());
            }
        });

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
    public RewriteResultDTO rewriteParagraph(String paperId, String paragraphId, String text, String deviceId) {
        int remaining = dailyUsageService.getRemaining(deviceId);
        if (remaining <= 0) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        boolean consumed = dailyUsageService.consume(deviceId);
        if (!consumed) {
            throw new RuntimeException("今日免费次数已用完，请先兑换");
        }

        Paragraph paragraph = paragraphService.getById(paragraphId);
        if (paragraph == null || !paragraph.getPaperId().equals(paperId)) {
            throw new IllegalArgumentException("段落不存在");
        }
        if (!paragraph.getCanRewrite()) {
            throw new IllegalArgumentException("该段落不允许润色");
        }

        String sourceText = (text != null && !text.isEmpty()) ? text : paragraph.getOriginalText();

        String rewrittenText = callAiRewrite(sourceText);

        paragraph.setRewrittenText(rewrittenText);
        paragraph.setStatus("rewritten");
        paragraphService.updateById(paragraph);

        RewriteResultDTO result = new RewriteResultDTO();
        result.setParagraphId(paragraphId);
        result.setRewrittenText(rewrittenText);
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
        paragraph.setStatus("replaced");
        paragraphService.updateById(paragraph);

        updateRewrittenCount(paperId);
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

    private String callAiRewrite(String text) {
        if (aiConfig.getBaseUrl() == null || aiConfig.getBaseUrl().isEmpty()
                || aiConfig.getApiKey() == null || aiConfig.getApiKey().isEmpty()) {
            throw new RuntimeException("AI 接口未配置，请在 application.yml 中配置 ai.openai 相关参数");
        }

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String body = "{\"model\":\"" + aiConfig.getModel() + "\","
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"你是一个学术论文润色助手。请对给定的段落进行降重润色，要求：1.保持原意不变 2.使用学术语言风格 3.保留专业术语 4.调整句式结构降低重复率 5.只返回润色后的文本，不要任何解释\"},"
                    + "{\"role\":\"user\",\"content\":\"" + escapeJson(text) + "\"}"
                    + "],\"temperature\":0.7}";

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(aiConfig.getBaseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getApiKey())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("AI 接口调用失败: " + response.body());
            }

            return parseAiResponse(response.body());
        } catch (Exception e) {
            throw new RuntimeException("AI 润色失败: " + e.getMessage(), e);
        }
    }

    private String parseAiResponse(String responseBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
            return root.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("解析 AI 响应失败: " + e.getMessage(), e);
        }
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
    public byte[] getPdfData(String paperId) {
        Paper paper = paperService.getById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("论文不存在: " + paperId);
        }
        if (paper.getPdfPath() == null || paper.getPdfPath().isEmpty()) {
            throw new IllegalArgumentException("PDF 尚未生成");
        }
        try (InputStream is = minioUtil.download(paper.getPdfPath())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("获取 PDF 失败: " + e.getMessage(), e);
        }
    }
}
