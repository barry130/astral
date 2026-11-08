package com.astral.ai.dto;

import lombok.Data;

/** A bounded retrieval projection; document bodies never enter the search result set. */
@Data
public class AiKnowledgeMatch {
    private Long documentId;
    private Long sourceId;
    private String sourceType;
    private String title;
    private String path;
    private String section;
    private String version;
    private Integer chunkNo;
    private String content;
    private String firstTerm;
    private Double score;
}
