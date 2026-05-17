package com.astral.sequence.service;

import com.astral.dao.entity.SequenceHistory;
import com.astral.dao.mapper.SequenceHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 序列历史记录服务
 * <p>
 * 负责序列号生成历史记录的保存和查询操作。
 * 历史记录用于审计、追踪和问题排查。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceHistoryService {

    /** 序列历史数据访问接口 */
    private final SequenceHistoryMapper historyMapper;

    /**
     * 保存序列号生成历史记录
     * <p>
     * 记录业务键、生成类型、序列号值和创建时间。
     * 如果保存失败，记录警告日志并重新抛出异常，由调用方决定如何处理。
     * </p>
     *
     * @param bizKey 业务键
     * @param type   生成器类型
     * @param value  序列号值
     */
    public void save(String bizKey, String type, long value) {
        try {
            SequenceHistory history = new SequenceHistory();
            history.setBizKey(bizKey);
            history.setSequenceType(type);
            history.setSequenceValue(value);
            history.setCreateTime(LocalDateTime.now());
            historyMapper.insert(history);
        } catch (Exception e) {
            log.warn("Failed to save sequence history: bizKey={}, type={}, value={}", bizKey, type, value, e);
            throw e;
        }
    }

    /**
     * 分页查询历史记录
     * <p>
     * 支持按业务键过滤，结果按创建时间倒序排列（最新的在前）。
     * </p>
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @param bizKey   可选的业务键过滤条件
     * @return 分页结果
     */
    public IPage<SequenceHistory> getPage(int pageNum, int pageSize, String bizKey) {
        Page<SequenceHistory> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SequenceHistory> wrapper = new LambdaQueryWrapper<>();
        if (bizKey != null && !bizKey.isEmpty()) {
            wrapper.eq(SequenceHistory::getBizKey, bizKey);
        }
        wrapper.orderByDesc(SequenceHistory::getCreateTime);
        return historyMapper.selectPage(page, wrapper);
    }

    /**
     * 获取最近的序列生成记录
     * <p>
     * 返回最近生成的序列号记录，限制数量有安全边界（1-1000），
     * 防止恶意请求导致一次性加载过多数据。
     * </p>
     *
     * @param bizKey 可选的业务键过滤条件
     * @param limit  请求的返回数量
     * @return 最近的历史记录列表
     */
    public List<SequenceHistory> getRecent(String bizKey, int limit) {
        // 限制范围在 1-1000 之间，防止过大或过小的值
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        LambdaQueryWrapper<SequenceHistory> wrapper = new LambdaQueryWrapper<>();
        if (bizKey != null && !bizKey.isEmpty()) {
            wrapper.eq(SequenceHistory::getBizKey, bizKey);
        }
        wrapper.orderByDesc(SequenceHistory::getCreateTime);
        wrapper.last("LIMIT " + safeLimit);
        return historyMapper.selectList(wrapper);
    }
}
