package com.astral.log.aspect;

import com.astral.dao.entity.OperateLog;
import com.astral.log.service.LogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 操作日志异步写入器。
 *
 * <p><b>为什么必须单独成一个 Bean</b>：Spring 的 {@code @Async} 是通过代理实现的，
 * 同类内部调用（{@code this.saveLogAsync(...)}）会绕过代理，注解完全不生效。
 * 此前 {@code @Async} 方法就写在 {@link OperateLogAspect} 里、又由它自己调用，
 * 因此日志一直是<b>同步落库</b>的——每个带 {@code @OperateLog} 的接口都要额外等一次
 * 数据库写入，与「不阻塞主流程」的设计意图相反。</p>
 *
 * <p>把异步方法搬到独立 Bean 后，切面注入它再调用，走的是代理，异步才真正生效。</p>
 *
 * <p>另外：异步方法返回 {@code void} 时，任务内的异常会被完全吞掉（连日志都没有）。
 * 这里返回 {@code CompletableFuture}，便于在链路上看到失败原因。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperateLogWriter {

    private final LogService logService;

    /**
     * 异步保存操作日志。
     *
     * @param username       操作用户名
     * @param operateType    操作类型
     * @param requestUrl     请求URL
     * @param requestMethod  请求方法
     * @param requestParams  请求参数（JSON）
     * @param responseResult 响应结果（JSON）
     * @param ip             客户端IP
     * @param status         1=成功 0=失败
     * @param errorMsg       错误信息
     * @param executeTime    执行耗时（毫秒）
     * @return 异步任务句柄
     */
    @Async("sequenceAsyncExecutor")
    public java.util.concurrent.CompletableFuture<Void> saveAsync(
            String username, String operateType, String requestUrl, String requestMethod,
            String requestParams, String responseResult, String ip, Integer status,
            String errorMsg, long executeTime) {
        try {
            save(username, operateType, requestUrl, requestMethod, requestParams,
                    responseResult, ip, status, errorMsg, executeTime);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            // 日志保存失败绝不能影响主流程，但要留下记录
            log.error("保存操作日志失败: operateType={}", operateType, e);
            java.util.concurrent.CompletableFuture<Void> f = new java.util.concurrent.CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    private void save(String username, String operateType, String requestUrl, String requestMethod,
                      String requestParams, String responseResult, String ip, Integer status,
                      String errorMsg, long executeTime) {
        log.debug("保存操作日志: operateType={}, status={}", operateType, status);
        OperateLog operateLog = new OperateLog();
        operateLog.setUsername(username);
        operateLog.setModule("API");
        operateLog.setOperateType(operateType);
        operateLog.setRequestUrl(requestUrl);
        operateLog.setRequestMethod(requestMethod);
        operateLog.setRequestParams(requestParams);
        operateLog.setResponseResult(responseResult);
        operateLog.setIp(ip);
        operateLog.setLocation("");
        operateLog.setStatus(status);
        operateLog.setErrorMsg(errorMsg);
        operateLog.setExecuteTime(executeTime);
        operateLog.setCreateTime(LocalDateTime.now());
        logService.saveOperateLog(operateLog);
    }
}
