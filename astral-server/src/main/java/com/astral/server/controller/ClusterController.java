package com.astral.server.controller;

import com.astral.common.error.ErrorCodes;
import com.astral.common.result.Result;
import com.astral.server.dto.ClusterNode;
import com.astral.server.dto.ClusterStatus;
import com.astral.server.service.ClusterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 集群管理控制器
 * <p>提供集群节点管理、健康检查、节点上下线等功能</p>
 */
@Tag(name = "集群管理")
@RestController
@RequestMapping("/api/v1/admin/cluster")
@RequiredArgsConstructor
public class ClusterController {

    /** 集群服务 */
    private final ClusterService clusterService;

    /**
     * 集群健康检查
     *
     * @return "ok"表示集群正常
     */
    @Operation(summary = "集群健康检查")
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("ok");
    }

    /**
     * 获取在线节点列表
     *
     * @return 当前在线的节点列表
     */
    @Operation(summary = "在线节点列表")
    @GetMapping("/nodes")
    public Result<List<ClusterNode>> getOnlineNode() {
        return Result.success(clusterService.getOnlineNodes());
    }

    /**
     * 获取所有节点列表（包含在线和离线）
     *
     * @return 全部节点列表
     */
    @Operation(summary = "所有节点列表")
    @GetMapping("/nodes/all")
    public Result<List<ClusterNode>> getAllNodes() {
        return Result.success(clusterService.getAllNodes());
    }

    /**
     * 获取集群整体状态信息
     *
     * @return 集群状态（包含节点数、健康状态等）
     */
    @Operation(summary = "集群状态")
    @GetMapping("/status")
    public Result<ClusterStatus> getStatus() {
        return Result.success(clusterService.getStatus());
    }

    /**
     * 获取当前节点信息
     *
     * @return 当前节点信息，集群未启用时返回错误
     */
    @Operation(summary = "当前节点信息")
    @GetMapping("/current")
    public Result<ClusterNode> getCurrentNode() {
        ClusterNode node = clusterService.getCurrentNode();
        if (node == null) {
            return Result.error("CLUSTER001");
        }
        return Result.success(node);
    }

    /**
     * 获取指定节点的状态信息
     *
     * @param nodeId 节点ID
     * @return 节点信息，节点不存在时返回错误
     */
    @Operation(summary = "指定节点状态")
    @GetMapping("/node/{nodeId}")
    public Result<ClusterNode> getNodeStatus(@PathVariable String nodeId) {
        ClusterNode node = clusterService.getNodeStatus(nodeId);
        if (node == null) {
            return Result.error("CLUSTER002");
        }
        return Result.success(node);
    }

    /**
     * 将指定节点下线
     *
     * @param nodeId 节点ID
     * @return 操作结果
     */
    @Operation(summary = "节点下线")
    @PostMapping("/node/{nodeId}/offline")
    public Result<Void> offlineNode(@PathVariable String nodeId) {
        clusterService.offlineNode(nodeId);
        return Result.success();
    }
}
