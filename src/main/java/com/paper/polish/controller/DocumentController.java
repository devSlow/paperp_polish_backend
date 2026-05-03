package com.paper.polish.controller;

import com.paper.polish.common.Result;
import com.paper.polish.dto.*;
import com.paper.polish.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public Result<UploadResultDTO> upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "deviceId", required = false) String deviceId) {
        if (file.isEmpty()) {
            return Result.fail(400, "文件不能为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".docx")) {
            return Result.fail(400, "仅支持 .docx 格式文件");
        }
        if (deviceId == null || deviceId.isEmpty()) {
            return Result.fail(400, "设备ID不能为空");
        }
        return Result.ok(documentService.upload(file, deviceId));
    }

    @GetMapping("/{paperId}")
    public Result<PaperInfoDTO> getPaperInfo(@PathVariable String paperId) {
        return Result.ok(documentService.getPaperInfo(paperId));
    }

    @GetMapping("/{paperId}/paragraphs")
    public Result<ParagraphListDTO> getParagraphs(@PathVariable String paperId) {
        return Result.ok(documentService.getParagraphs(paperId));
    }

    @PostMapping("/{paperId}/paragraph/{paragraphId}/rewrite")
    public Result<RewriteResultDTO> rewriteParagraph(@PathVariable String paperId,
                                                      @PathVariable String paragraphId,
                                                      @RequestBody RewriteRequest request) {
        String deviceId = request.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            return Result.fail(400, "设备ID不能为空");
        }
        int round = request.getRound() != null ? request.getRound() : 1;
        return Result.ok(documentService.rewriteParagraph(paperId, paragraphId, request.getText(), deviceId, request.getSelectedText(), round));
    }

    @PostMapping("/rewrite/text")
    public Result<RewriteResultDTO> rewriteTextOnly(@RequestBody RewriteRequest request) {
        String deviceId = request.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            return Result.fail(400, "设备ID不能为空");
        }
        String text = request.getText();
        if (text == null || text.isEmpty()) {
            return Result.fail(400, "文本不能为空");
        }
        int round = request.getRound() != null ? request.getRound() : 1;
        return Result.ok(documentService.rewriteTextOnly(text, deviceId, round));
    }

    public static class RewriteRequest {
        private String deviceId;
        private String text;
        private String selectedText;
        private Integer round;

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getSelectedText() { return selectedText; }
        public void setSelectedText(String selectedText) { this.selectedText = selectedText; }
        public Integer getRound() { return round; }
        public void setRound(Integer round) { this.round = round; }
    }

    @PostMapping("/{paperId}/paragraph/{paragraphId}/accept")
    public Result<RewriteResultDTO> acceptParagraph(@PathVariable String paperId,
                                                     @PathVariable String paragraphId,
                                                     @RequestBody(required = false) AcceptRequestDTO request) {
        documentService.acceptParagraph(paperId, paragraphId, request);
        RewriteResultDTO scoreResult = new RewriteResultDTO();
        try {
            RewriteResultDTO.ScoreResult score = documentService.scoreParagraph(paragraphId);
            scoreResult.setScore(score);
        } catch (Exception e) {
            log.warn("评分失败: {}", e.getMessage());
        }
        return Result.ok(scoreResult);
    }

    @PostMapping("/{paperId}/paragraph/{paragraphId}/reject")
    public Result<Void> rejectParagraph(@PathVariable String paperId,
                                         @PathVariable String paragraphId) {
        documentService.rejectParagraph(paperId, paragraphId);
        return Result.ok();
    }

    @PostMapping("/{paperId}/paragraph/{paragraphId}/score")
    public Result<RewriteResultDTO.ScoreResult> scoreParagraph(@PathVariable String paperId,
                                                                @PathVariable String paragraphId,
                                                                @RequestBody ScoreRequest request) {
        try {
            String originalText = request.getOriginalText();
            String rewrittenText = request.getRewrittenText();
            if (originalText == null || rewrittenText == null) {
                return Result.fail(400, "原文和润色文本不能为空");
            }
            RewriteResultDTO.ScoreResult score = documentService.callAiScore(originalText, rewrittenText);
            return Result.ok(score);
        } catch (Exception e) {
            log.warn("评分失败: {}", e.getMessage());
            return Result.fail(500, "评分失败: " + e.getMessage());
        }
    }

    public static class ScoreRequest {
        private String originalText;
        private String rewrittenText;
        public String getOriginalText() { return originalText; }
        public void setOriginalText(String originalText) { this.originalText = originalText; }
        public String getRewrittenText() { return rewrittenText; }
        public void setRewrittenText(String rewrittenText) { this.rewrittenText = rewrittenText; }
    }

    @PostMapping("/{paperId}/export")
    public Result<ExportResultDTO> exportDocument(@PathVariable String paperId) {
        return Result.ok(documentService.exportDocument(paperId));
    }

}
