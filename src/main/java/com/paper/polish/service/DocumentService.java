package com.paper.polish.service;

import com.paper.polish.dto.*;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    UploadResultDTO upload(MultipartFile file);

    PaperInfoDTO getPaperInfo(String paperId);

    ParagraphListDTO getParagraphs(String paperId);

    RewriteResultDTO rewriteParagraph(String paperId, String paragraphId, RewriteRequestDTO request);

    void acceptParagraph(String paperId, String paragraphId, AcceptRequestDTO request);

    void rejectParagraph(String paperId, String paragraphId);

    ExportResultDTO exportDocument(String paperId);

    byte[] getPdfData(String paperId);
}
