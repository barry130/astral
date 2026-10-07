package com.astral.qt.mapper;

import com.astral.qt.entity.QtLikePlaylist;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QtLikePlaylistMapper extends BaseMapper<QtLikePlaylist> {

    /**
     * 收藏歌单：全量唯一键 upsert（uk_like_playlist_key）。
     * 冲突时复活软删行（deleted_at 置空）、刷新封面/名称并推进 seq；空字段不覆盖已有元数据。
     */
    @Insert({"""
            INSERT INTO qt_like_playlist
                (id, uid, pid, platform, pic_url, name, is_import, deleted_at,
                 create_time, update_time, updated_seq, updated_at)
            VALUES
                (#{e.id}, #{e.uid}, #{e.pid}, #{e.platform}, #{e.picUrl}, #{e.name},
                 COALESCE(#{e.isImport}, 0), NULL, #{now}, #{now}, #{seq}, #{now})
            ON CONFLICT (uid, pid, platform) DO UPDATE SET
                deleted_at = NULL,
                pic_url = COALESCE(EXCLUDED.pic_url, qt_like_playlist.pic_url),
                name = COALESCE(EXCLUDED.name, qt_like_playlist.name),
                updated_seq = EXCLUDED.updated_seq,
                updated_at = EXCLUDED.updated_at,
                update_time = EXCLUDED.update_time
            """})
    int upsertActive(@Param("e") QtLikePlaylist e, @Param("seq") long seq, @Param("now") LocalDateTime now);

    /**
     * 批量收藏歌单（/like/batch 使用）：多行 VALUES + ON CONFLICT，每行携带自己的 updated_seq。
     * 冲突语义与 {@link #upsertActive} 完全一致（复活软删行、空字段不覆盖）。
     * <p>调用方必须保证 list 内 (uid, pid, platform) 唯一——同语句同键两次会触发
     * PG 「cannot affect row a second time」错误。id 由号段填充器对集合参数逐个自动填充。</p>
     */
    @Insert({
            """
            <script>
            INSERT INTO qt_like_playlist
                (id, uid, pid, platform, pic_url, name, is_import, deleted_at,
                 create_time, update_time, updated_seq, updated_at)
            VALUES
            <foreach item='it' index='index' collection='list' separator=','>
                (#{it.id}, #{it.uid}, #{it.pid}, #{it.platform}, #{it.picUrl}, #{it.name},
                 COALESCE(#{it.isImport}, 0), NULL, #{it.createTime}, #{it.updateTime}, #{it.updatedSeq}, #{it.updatedAt})
            </foreach>
            ON CONFLICT (uid, pid, platform) DO UPDATE SET
                deleted_at = NULL,
                pic_url = COALESCE(EXCLUDED.pic_url, qt_like_playlist.pic_url),
                name = COALESCE(EXCLUDED.name, qt_like_playlist.name),
                updated_seq = EXCLUDED.updated_seq,
                updated_at = EXCLUDED.updated_at,
                update_time = EXCLUDED.update_time
            </script>
            """})
    int upsertActiveBatch(@Param("list") List<QtLikePlaylist> list);

    /**
     * 批量取消收藏歌单（/like/batch 使用）：按 (pid, platform) 逐行软删，
     * 每行携带自己的 updated_seq，幂等（已删除行不再变更）。
     * <p>调用方必须保证 list 内 (pid, platform) 唯一。</p>
     */
    @Update({
            """
            <script>
            UPDATE qt_like_playlist AS l
            SET deleted_at = #{now}, update_time = #{now}, updated_at = #{now}, updated_seq = v.seq
            FROM (
                VALUES
                <foreach item='it' index='index' collection='list' separator=','>
                    (CAST(#{it.pid} AS VARCHAR), CAST(#{it.platform} AS VARCHAR), CAST(#{it.updatedSeq} AS BIGINT))
                </foreach>
            ) AS v(pid, platform, seq)
            WHERE l.uid = #{uid}
              AND l.pid = v.pid AND l.platform = v.platform
              AND l.deleted_at IS NULL
            </script>
            """})
    int softRemoveBatch(@Param("list") List<QtLikePlaylist> list, @Param("uid") Long uid,
                        @Param("now") LocalDateTime now);

    /** 取消收藏歌单：软删除并推进 seq（幂等：已删除行不再变更） */
    @Update("""
            UPDATE qt_like_playlist
            SET deleted_at = #{now}, update_time = #{now}, updated_seq = #{seq}, updated_at = #{now}
            WHERE uid = #{uid} AND pid = #{pid} AND platform = #{platform} AND deleted_at IS NULL
            """)
    int softRemove(@Param("uid") Long uid, @Param("pid") String pid, @Param("platform") String platform,
                   @Param("seq") long seq, @Param("now") LocalDateTime now);

    /** 用户在歌单表的最大变更序号（无记录返回 0） */
    @Select("SELECT COALESCE(MAX(updated_seq), 0) FROM qt_like_playlist WHERE uid = #{uid}")
    Long selectMaxSeq(@Param("uid") Long uid);
}
