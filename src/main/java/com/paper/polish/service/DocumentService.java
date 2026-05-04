package com.paper.polish.service;

import com.paper.polish.dto.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;

public interface DocumentService {

    UploadResultDTO upload(MultipartFile file, String deviceId);

    PaperInfoDTO getPaperInfo(String paperId);

    ParagraphListDTO getParagraphs(String paperId);

    RewriteResultDTO rewriteParagraph(String paperId, String paragraphId, String text, String deviceId, String selectedText);

    RewriteResultDTO rewriteParagraph(String paperId, String paragraphId, String text, String deviceId, String selectedText, int round);

    RewriteResultDTO rewriteTextOnly(String text, String deviceId, int round);

    void acceptParagraph(String paperId, String paragraphId, AcceptRequestDTO request);

    RewriteResultDTO.ScoreResult scoreParagraph(String paragraphId);

    RewriteResultDTO.ScoreResult callAiScore(String originalText, String rewrittenText);

    void rejectParagraph(String paperId, String paragraphId);

    ExportResultDTO exportDocument(String paperId);

    PptGenerateResultDTO generatePpt(String paperId, String deviceId, HttpServletRequest httpRequest);
}
