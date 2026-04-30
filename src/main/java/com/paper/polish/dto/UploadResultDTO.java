package com.paper.polish.dto;

import lombok.Data;

@Data
public class UploadResultDTO {

    private String paperId;
    private String status;
    private Integer paragraphCount;
}
