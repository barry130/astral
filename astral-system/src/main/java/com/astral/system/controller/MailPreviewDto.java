package com.astral.system.controller;

import lombok.Data;

import java.util.Map;

@Data
public class MailPreviewDto {
    private Long templateId;
    private Map<String, String> variables;
}
