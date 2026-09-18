package com.astral.qt.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 增量拉取结果（LIKE_SYNC_DESIGN.md §2.3）
 * <p>changes 按 updated_seq 升序；maxSeq 为当前用户全部收藏的最大变更序号，客户端拉取后将其存为同步游标。</p>
 */
@Data
public class QtLikeChangesVo {

    /** since 之后的变更列表（含删除事件） */
    private List<QtLikeChangeVo> changes;

    /** 当前用户最大变更序号，作为下次 since 游标 */
    private Long maxSeq;
}
