package com.paper.polish.dto;

import lombok.Data;

import java.util.List;

@Data
public class PptGenerateDTO {

    private String paperId;

    private String title;

    private List<PptSlideDTO> slides;

    @Data
    public static class PptSlideDTO {
        private String layout;
        private String title;
        private String subtitle;
        private List<String> bullets;
    }
}
