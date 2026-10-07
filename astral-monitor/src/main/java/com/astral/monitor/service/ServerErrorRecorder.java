package com.astral.monitor.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.astral.dao.entity.StatErrorLog;
import com.astral.dao.mapper.StatErrorLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 服务端异常登记器
 *
 * <p>此前 stat_error_log 只收客户端上报（AppStatController 的 error 事件），
 * 服务端自身的 500 只进应用日志，管理台「错误统计」里完全看不见后端炸了没。
 * {@code GlobalExceptionHandler} 的兜底 500 处理器调用本类把异常同步登记进
 * 同一张表，来源标为 {@code server}，与客户端错误共用指纹分组与清理策略。</p>
 *
 * <p>登记本身必须绝不抛异常（它跑在异常处理器里，二次异常会掩盖原始错误），
 * 因此全程 try/catch，失败仅记日志。异步走全局 sequenceAsyncExecutor，
 * 不拖慢错误响应。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
public class ServerErrorRecorder {

    /** 服务端错误在 ut 维度上的固定取值（前端筛选项与客户端平台并列展示） */
    public static final String SOURCE_SERVER = "server";

    /** 错误堆栈最大长度（与客户端上报保持一致：16KB 截断） */
    private static final int MAX_STACK_LENGTH = 16 * 1024;

    /** message 最大长度（DDL VARCHAR(1024)） */
    private static final int MAX_MESSAGE_LENGTH = 1024;

    @Autowired(required = false)
    private StatErrorLogMapper statErrorLogMapper;

    /**
     * 登记一次服务端异常（异步、绝不抛出）
     *
     * @param e      异常
     * @param uri    请求 URI（无请求上下文时可为 null）
     * @param method HTTP 方法（可为 null）
     */
    @org.springframework.scheduling.annotation.Async("sequenceAsyncExecutor")
    public void recordAsync(Exception e, String uri, String method) {
        try {
            if (statErrorLogMapper == null) {
                return;
            }
            String stack = stackToString(e);
            if (stack.length() > MAX_STACK_LENGTH) {
                stack = stack.substring(0, MAX_STACK_LENGTH);
            }
            // message = 异常类名 + 消息，保指纹分组稳定；超长截断
            String message = e.toString();
            if (message.length() > MAX_MESSAGE_LENGTH) {
                message = message.substring(0, MAX_MESSAGE_LENGTH);
            }
            // 指纹口径与 StatIngestService.computeFingerprint 对齐：errorType|message|栈首行
            String errorType = SOURCE_SERVER;
            String firstStackLine = stack.isBlank() ? "" : stack.strip().split("\n", 2)[0];
            String fingerprint = DigestUtil.md5Hex(errorType + "|" + message + "|" + firstStackLine);

            StatErrorLog errorLog = new StatErrorLog();
            errorLog.setFingerprint(fingerprint);
            errorLog.setErrorType(errorType);
            errorLog.setSource(SOURCE_SERVER);
            errorLog.setMessage(message);
            errorLog.setStack(stack);
            // page 存「METHOD uri」，复用客户端错误的 page 维度
            errorLog.setPage((method == null ? "" : method) + " " + (uri == null ? "" : uri));
            errorLog.setUt(SOURCE_SERVER);
            errorLog.setOccurTime(LocalDateTime.now());
            statErrorLogMapper.insert(errorLog);
        } catch (Exception recordError) {
            // 登记失败绝不能掩盖原始异常，吞掉只记日志
            log.warn("[stat] 服务端异常登记失败（原始异常仍由应用日志承载）: {}", recordError.getMessage());
        }
    }

    private String stackToString(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
