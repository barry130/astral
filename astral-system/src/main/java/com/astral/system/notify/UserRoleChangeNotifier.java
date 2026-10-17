package com.astral.system.notify;

import com.astral.dao.entity.Role;
import com.astral.dao.mapper.RoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户角色差异通知：只在业务事务提交后异步投递，通知异常不影响角色维护。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRoleChangeNotifier {

    private final RoleMapper roleMapper;
    private final NotifyPublisher notifyPublisher;

    public void notifyChange(Long userId, List<Long> before, List<Long> after) {
        if (userId == null) {
            return;
        }
        Set<Long> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        Set<Long> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        try {
            Set<Long> changed = new LinkedHashSet<>(added);
            changed.addAll(removed);
            Map<Long, String> names = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                            .in(Role::getId, changed)).stream()
                    .collect(Collectors.toMap(Role::getId, r -> r.getRoleName() == null || r.getRoleName().isBlank()
                            ? r.getRoleCode() == null ? String.valueOf(r.getId()) : r.getRoleCode()
                            : r.getRoleName()));
            String addedNames = roleNames(added, names);
            String removedNames = roleNames(removed, names);
            String summary = (added.isEmpty() ? "" : "您已获得" + addedNames + "角色权限。")
                    + (removed.isEmpty() ? "" : "您已被移除" + removedNames + "角色权限。");
            Map<String, String> payload = Map.of("userId", userId.toString(), "addedRoles", addedNames,
                    "removedRoles", removedNames, "changeSummary", summary);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish(payload);
                    }
                });
            } else {
                publish(payload);
            }
        } catch (Exception e) {
            log.warn("[Notify] 用户 {} 的角色变更通知准备失败", userId, e);
        }
    }

    private String roleNames(Set<Long> ids, Map<Long, String> names) {
        return ids.stream().map(id -> "「" + names.getOrDefault(id, "角色#" + id) + "」")
                .collect(Collectors.joining("、"));
    }

    private void publish(Map<String, String> payload) {
        try {
            notifyPublisher.publishAsync(NotifyEventRegistry.USER_ROLES_CHANGED, payload);
        } catch (Exception e) {
            log.warn("[Notify] 用户 {} 的角色变更通知发布失败", payload.get("userId"), e);
        }
    }
}
