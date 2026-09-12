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

    /** 批量软删除（旧全量接口使用），同时写入 updated_seq/updated_at 纳入多端增量同步 */
    @Update({"""
            <script>
            UPDATE qt_like_playlist
            SET deleted_at = #{time}, update_time = #{time}, updated_seq = #{seq}, updated_at = #{time}
            WHERE uid = #{uid} AND deleted_at IS NULL
              AND (pid, platform) IN
              <foreach item='it' index='index' collection='tuples' open='(' separator=',' close=')'>
                (#{it.id}, #{it.platform})
              </foreach>
            </script>
            """})
    int softDelete(@Param("tuples") List<QtPlaylistTuple> tuples, @Param("time") LocalDateTime time,
                   @Param("uid") Long uid, @Param("seq") long seq);

    /** 批量插入新增收藏（旧全量接口使用），id/seq/时间由调用方显式填充 */
    @Insert({"""
            <script>
            INSERT INTO qt_like_playlist
                (id, uid, pid, platform, pic_url, name, is_import, deleted_at,
                 create_time, update_time, updated_seq, updated_at)
            VALUES
            <foreach item='it' index='index' collection='list' separator=','>
                (#{it.id}, #{it.uid}, #{it.pid}, #{it.platform}, #{it.picUrl}, #{it.name}, #{it.isImport}, NULL,
                 #{it.createTime}, #{it.updateTime}, #{it.updatedSeq}, #{it.updatedAt})
            </foreach>
            </script>
            """})
    int insertBatch(@Param("list") List<QtLikePlaylist> list);

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

    class QtPlaylistTuple {
        public String id;
        public String platform;
    }
}
