package com.paper.polish.dto;

import lombok.Data;

import java.util.List;

@Data
public class ParagraphListDTO {

    private String paperId;
    private Integer paragraphCount;
    private List<ParagraphItemDTO> paragraphs;

    @Data
    public static class ParagraphItemDTO {
        private String id;
        private Integer paragraphIndex;
        private String type;
        private String contentType;
        private String locationType;
        private String originalText;
        private String currentText;
        private String rewrittenText;
        private String imageUrl;
        private String tableData;
        private String styleData;
        private String htmlContent;
        private Boolean canRewrite;
        private String status;
    }
}
