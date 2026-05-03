package com.paper.polish.dto;

import lombok.Data;

import java.util.Map;

@Data
public class RewriteResultDTO {

    private String paragraphId;
    private String rewrittenText;
    private Integer round;
    private ScoreResult score;

    @Data
    public static class ScoreResult {
        private Integer directness;
        private Integer rhythm;
        private Integer trustworthiness;
        private Integer authenticity;
        private Integer conciseness;
        private Integer semanticFidelity;
        private Integer purity;
        private Integer total;
        private String level;
    }
}
