package com.astral.ai.service;

import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.mapper.AiMcpServerMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 MCP 刷新失败的原因写回 {@code ai_mcp_server}。
 *
 * <p>{@code AiMcpServerService.refresh} 是事务方法，失败路径上「写 last_error 再抛异常」
 * 会被自身事务回滚掉，页面上看不到任何失败痕迹。这里用独立事务（{@code REQUIRES_NEW}）
 * 落盘，保证失败原因一定可见，同时不影响主流程的回滚语义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMcpServerFailureRecorder {

    private final AiMcpServerMapper serverMapper;

    /** 记录一次刷新失败（不超过 500 字），独立事务提交 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(Long serverId, String error, Long operatorId) {
        if (serverId == null) {
            return;
        }
        AiMcpServerEntity entity = new AiMcpServerEntity();
        entity.setId(serverId);
        entity.setLastError(AiJson.truncate(error, 500));
        entity.setLastRefreshTime(LocalDateTime.now());
        entity.setUpdatedBy(operatorId);
        int updated = serverMapper.updateById(entity);
        if (updated == 0) {
            log.warn("[AI] MCP 服务器刷新失败原因未能写回: {}", serverId);
        }
    }
}
