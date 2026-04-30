package com.paper.polish.controller;

import com.paper.polish.common.Result;
import com.paper.polish.dto.*;
import com.paper.polish.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public Result<UploadResultDTO> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.fail(400, "文件不能为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".docx")) {
            return Result.fail(400, "仅支持 .docx 格式文件");
        }
        return Result.ok(documentService.upload(file));
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
                                                      @RequestBody(required = false) RewriteRequestDTO request) {
        return Result.ok(documentService.rewriteParagraph(paperId, paragraphId, request));
    }

    @PostMapping("/{paperId}/paragraph/{paragraphId}/accept")
    public Result<Void> acceptParagraph(@PathVariable String paperId,
                                         @PathVariable String paragraphId,
                                         @RequestBody(required = false) AcceptRequestDTO request) {
        documentService.acceptParagraph(paperId, paragraphId, request);
        return Result.ok();
    }

    @PostMapping("/{paperId}/paragraph/{paragraphId}/reject")
    public Result<Void> rejectParagraph(@PathVariable String paperId,
                                         @PathVariable String paragraphId) {
        documentService.rejectParagraph(paperId, paragraphId);
        return Result.ok();
    }

    @PostMapping("/{paperId}/export")
    public Result<ExportResultDTO> exportDocument(@PathVariable String paperId) {
        return Result.ok(documentService.exportDocument(paperId));
    }

    @GetMapping("/{paperId}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable String paperId) {
        byte[] pdfData = documentService.getPdfData(paperId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + paperId + ".pdf\"")
                .body(pdfData);
    }
}
