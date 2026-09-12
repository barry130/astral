package com.astral.qt.mapper;

import com.astral.qt.dto.vo.QtLikeChangeVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 收藏多端同步专用 Mapper（LIKE_SYNC_DESIGN.md §1.3/§2.3）
 * <p>不绑定单表实体：跨 qt_like_song / qt_like_playlist 取序号、加锁、查增量。</p>
 */
@Mapper
public interface QtLikeSyncMapper {

    /**
     * 事务级咨询锁：同一用户收藏操作串行化，避免并发取号冲突（事务提交/回滚自动释放）。
     * <p>注意：PG 对 void 函数的 SELECT 会返回函数名字符串，故声明为 String 接收。</p>
     */
    @Select("SELECT pg_advisory_xact_lock(#{key})")
    String lockUser(@Param("key") long key);

    /** 用户跨两表的最大变更序号（无记录返回 0） */
    @Select("""
            SELECT GREATEST(
                COALESCE((SELECT MAX(updated_seq) FROM qt_like_song WHERE uid = #{uid}), 0),
                COALESCE((SELECT MAX(updated_seq) FROM qt_like_playlist WHERE uid = #{uid}), 0)
            )
            """)
    Long selectUserMaxSeq(@Param("uid") Long uid);

    /**
     * 增量拉取：since 之后歌曲 + 歌单的变更（含删除事件），按 updated_seq 升序。
     * <p>deleted 列由 deleted_at 推导；type 区分 song/playlist，id 为对应 sid/pid；
     * song 行同时返回 pid（歌曲所属歌单，收藏时上送）与 pic_url（封面快照，可空），
     * playlist 行 pid 即歌单ID、pic_url 为歌单封面。</p>
     */
    @Select("""
            <script>
            SELECT 'song' AS type, sid AS id, sid, pid, platform, name, singer, album, hash,
                   pic_url, (deleted_at IS NOT NULL) AS deleted, updated_seq
            FROM qt_like_song
            WHERE uid = #{uid} AND updated_seq &gt; #{since}
            UNION ALL
            SELECT 'playlist' AS type, pid AS id, NULL AS sid, pid, platform, name,
                   NULL AS singer, NULL AS album, NULL AS hash, pic_url,
                   (deleted_at IS NOT NULL) AS deleted, updated_seq
            FROM qt_like_playlist
            WHERE uid = #{uid} AND updated_seq &gt; #{since}
            ORDER BY updated_seq ASC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<QtLikeChangeVo> selectChanges(@Param("uid") Long uid, @Param("since") long since,
                                       @Param("limit") int limit, @Param("offset") int offset);
}
