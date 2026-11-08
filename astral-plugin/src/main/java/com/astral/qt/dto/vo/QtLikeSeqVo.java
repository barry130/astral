package com.astral.qt.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏/取消收藏操作结果（LIKE_SYNC_DESIGN.md §2.1/§2.2）
 * <p>返回本次操作分配的 seq，客户端可将同步游标推进至该值。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QtLikeSeqVo {

    /** 本次操作后的变更序号（用户维度递增） */
    private Long seq;

    public static QtLikeSeqVo of(long seq) {
        return new QtLikeSeqVo(seq);
    }
}
