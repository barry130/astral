package com.astral.plugindemo;

import com.astral.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Tag(name = "示例插件")
@RestController
@RequestMapping("/api/v1/admin/plugin/demo")
public class DemoPluginController {

    @Operation(summary = "示例接口")
    @GetMapping("/hello")
    public Result<Map<String, String>> hello() {
        Map<String, String> data = new HashMap<>();
        data.put("message", "Hello from demo plugin");
        data.put("status", "running");
        return Result.success(data);
    }
}