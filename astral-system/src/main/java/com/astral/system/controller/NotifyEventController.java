package com.astral.system.controller;

import java.util.List;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.system.notify.NotifyChannel;
import com.astral.system.notify.NotifyEventDef;
import com.astral.system.notify.NotifyEventRegistry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "通知事件")
@RestController
@RequestMapping("/api/v1/admin/system/notify")
public class NotifyEventController {

    /**
     * 查看权限沿用 sys_permission: admin:system:mail:view——事件列表当前只服务于「邮箱管理」
     * 页（模板绑定/发信授权两处下拉），不新增权限码以免存量管理员角色漏授；
     * 消息中心成型（阶段②③）时再随渠道一并归位。
     */
    private static final String PERM_VIEW = "admin:system:mail:view";

    @Operation(summary = "事件列表", description = "代码注册的通知事件（含 payload 字段与可用渠道）：模板绑 scene、授权选 allowed_scenes 都从这里取")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/event/list")
    public Result<List<NotifyEventDef>> listEvents() {
        return Result.success(NotifyEventRegistry.list());
    }

    @Operation(summary = "渠道列表", description = "已注册的通知渠道（模板 channel 的合法值）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/channel/list")
    public Result<List<String>> listChannels() {
        return Result.success(NotifyChannel.registered());
    }
}
