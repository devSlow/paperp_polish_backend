package com.paper.polish.dto;

import lombok.Data;

@Data
public class PptGenerateResultDTO {

    private String paperId;

    private String downloadUrl;

    private int slideCount;
}
