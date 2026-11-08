package com.astral.ai.security;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.auth.security.PermissionChecker;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具执行前的权限咽喉（编排 {@code invoke} 与后台试跑共用同一份，不得复制两套）。
 *
 * <p>两条规则，与既有编排执行期校验同源；两组各自是 ANY 语义（命中任一即通过），
 * 两组之间是 AND（与 {@code @RequiresPermission} 的 {@code Mode.ANY}/{@code Mode.ALL} 家族一致）：</p>
 * <ol>
 *   <li>处理器在代码里注册的权限下限（{@code definition().permissionCodes()}）必须被调用者持有——
 *       后台把 {@code ai_tool.permission_codes} 清空也拿不掉代码下限；注册码是代码期常量，
 *       不经 CSV 解析器：写错的码只会让非持有者被拒，不会把工具变成全员（含超管）不可用的死工具；</li>
 *   <li>{@code ai_tool.permission_codes} 若与注册下限不是同一集合，作为追加要求同样必须被持有
 *       （按 {@code Set} 比较，顺序无关；两组均 {@code LinkedHashSet} 去重保序）。</li>
 * </ol>
 *
 * <p>身份必须是可信来源（{@code ai_request.user_id} 或 {@code AiCaller.requireUserId}），
 * 不得读模型参数或线程上下文里的自报身份；权限码存储形态异常时 fail-closed。</p>
 */
@Component
@RequiredArgsConstructor
public class AiToolPermissionGate {

    private final PermissionChecker permissionChecker;

    public void requireAdministratorApproval(Long userId) {
        if (userId == null || !permissionChecker.hasAnyPermissionByLoginId(userId, "admin:ai:tool:approve")) {
            throw new AiException("AI008", "需要工具审批权限");
        }
    }

    /** 校验结论 */
    public enum Outcome {
        /** 允许执行 */
        ALLOWED,
        /** 权限配置形态异常（fail-closed，不执行） */
        MALFORMED,
        /** 调用者未持有所需权限 */
        DENIED
    }

    /**
     * 校验调用者是否可执行该工具。
     *
     * @param userId  可信来源的用户 ID（执行期实时权限）
     * @param tool    工具记录
     * @param handler 已解析的处理器
     * @return 校验结论
     */
    public Outcome check(Long userId, AiToolEntity tool, AiToolHandler handler) {
        Set<String> permissions;
        try {
            permissions = new LinkedHashSet<>(AiJson.splitPermissionCsv(tool.getPermissionCodes()));
        } catch (AiException invalidPermissions) {
            return Outcome.MALFORMED;
        }
        List<String> declared = handler.definition().permissionCodes();
        Set<String> registeredPermissions =
                declared == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(declared);
        if (!registeredPermissions.isEmpty()
                && !permissionChecker.hasAnyPermissionByLoginId(userId, registeredPermissions.toArray(new String[0]))) {
            return Outcome.DENIED;
        }
        if (!permissions.isEmpty()
                && !permissions.equals(registeredPermissions)
                && !permissionChecker.hasAnyPermissionByLoginId(userId, permissions.toArray(new String[0]))) {
            return Outcome.DENIED;
        }
        return Outcome.ALLOWED;
    }
}
