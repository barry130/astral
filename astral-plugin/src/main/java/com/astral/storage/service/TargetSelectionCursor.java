package com.astral.storage.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * ROUND_ROBIN 的**本地有界轮询游标**。
 *
 * <p>游标键是 {@code folderId + folderVersion + priority}：目录目标版本变化
 * （绑定/策略/授权/状态/可见性变更）时旧键自然失效，不需要额外清理。
 * 只有 {@code resolveForUpload} 真正为上传意图选路时才推进；只读解析（列表展示、
 * 管理端预览）不推进。单实例本地状态足够；多实例一致性不在承诺范围内。</p>
 */
@Component
public class TargetSelectionCursor {

    /** 每层保留的游标数量上限，防止长期运行的内存增长 */
    private static final int MAX_KEYS = 4096;

    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    /**
     * 取当前轮询起点并推进一格。
     *
     * @param folderId 目录 ID（游标作用域）
     * @param version  目录 target_version
     * @param priority 优先级层
     * @param size     该层候选数量
     * @return 本轮首项下标（0-based）
     */
    public int nextStart(long folderId, long version, int priority, int size) {
        if (size <= 1) {
            return 0;
        }
        if (cursors.size() >= MAX_KEYS) {
            cursors.clear();
        }
        String key = folderId + ":" + version + ":" + priority;
        int index = Math.floorMod(
                cursors.computeIfAbsent(key, k -> new AtomicInteger()).getAndIncrement(), size);
        return index;
    }

    /** 读取当前起点但不推进。 */
    public int peekStart(long folderId, long version, int priority, int size) {
        if (size <= 1) {
            return 0;
        }
        AtomicInteger cursor = cursors.get(folderId + ":" + version + ":" + priority);
        int value = cursor == null ? 0 : cursor.get();
        return Math.floorMod(value, size);
    }
}
