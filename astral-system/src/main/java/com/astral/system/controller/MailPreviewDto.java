package com.astral.system.controller;

import java.util.Map;
import lombok.Data;

@Data
public class MailPreviewDto {
    private Long templateId;
    private Map<String, String> variables;
}
