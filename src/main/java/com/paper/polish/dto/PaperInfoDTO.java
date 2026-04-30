package com.paper.polish.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PaperInfoDTO {

    private String paperId;
    private String status;
    private Integer paragraphCount;
    private Integer rewrittenCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
