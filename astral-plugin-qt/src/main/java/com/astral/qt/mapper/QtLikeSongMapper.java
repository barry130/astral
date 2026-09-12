package com.astral.qt.mapper;

import com.astral.qt.entity.QtLikeSong;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QtLikeSongMapper extends BaseMapper<QtLikeSong> {

    /** 批量软删除（旧全量接口使用），同时写入 updated_seq/updated_at 纳入多端增量同步 */
    @Update({"""
            <script>
            UPDATE qt_like_song
            SET deleted_at = #{time}, update_time = #{time}, updated_seq = #{seq}, updated_at = #{time}
            WHERE uid = #{uid} AND deleted_at IS NULL
              AND (sid, platform) IN
              <foreach item='it' index='index' collection='tuples' open='(' separator=',' close=')'>
                (#{it.id}, #{it.platform})
              </foreach>
            </script>
            """})
    int softDelete(@Param("tuples") List<QtSongTuple> tuples, @Param("time") LocalDateTime time,
                   @Param("uid") Long uid, @Param("seq") long seq);

    /** 批量插入新增收藏（旧全量接口使用），id/seq/时间由调用方显式填充 */
    @Insert({"""
            <script>
            INSERT INTO qt_like_song
                (id, uid, sid, pid, platform, name, singer, album, hash, pic_url, deleted_at,
                 create_time, update_time, updated_seq, updated_at)
            VALUES
            <foreach item='it' index='index' collection='list' separator=','>
                (#{it.id}, #{it.uid}, #{it.sid}, #{it.pid}, #{it.platform}, #{it.name}, #{it.singer}, #{it.album},
                 #{it.hash}, #{it.picUrl}, NULL, #{it.createTime}, #{it.updateTime}, #{it.updatedSeq}, #{it.updatedAt})
            </foreach>
            </script>
            """})
    int insertBatch(@Param("list") List<QtLikeSong> list);

    /**
     * 收藏单曲：按 (uid, sid, platform, pid) 全量唯一键 upsert（uk_like_song_key_pid）。
     * 同一歌曲可收藏到多个歌单（每个歌单一行，pid='' 表示不归属具体歌单）。
     * 冲突时复活软删行（deleted_at 置空）、刷新元数据并推进 seq；空字段不覆盖已有元数据。
     * <p>封面（LIKE_SONG_PIC_SYNC_DESIGN.md D4）：仅当新值非空时覆盖，
     * 新值为空/null 时保留库中旧值，防止旧客户端把已有封面擦掉。</p>
     */
    @Insert({"""
            INSERT INTO qt_like_song
                (id, uid, sid, pid, platform, name, singer, album, hash, pic_url, deleted_at,
                 create_time, update_time, updated_seq, updated_at)
            VALUES
                (#{e.id}, #{e.uid}, #{e.sid}, #{e.pid}, #{e.platform}, #{e.name}, #{e.singer}, #{e.album},
                 #{e.hash}, #{e.picUrl}, NULL, #{now}, #{now}, #{seq}, #{now})
            ON CONFLICT (uid, sid, platform, pid) DO UPDATE SET
                deleted_at = NULL,
                name = COALESCE(EXCLUDED.name, qt_like_song.name),
                singer = COALESCE(EXCLUDED.singer, qt_like_song.singer),
                album = COALESCE(EXCLUDED.album, qt_like_song.album),
                hash = COALESCE(EXCLUDED.hash, qt_like_song.hash),
                pic_url = COALESCE(NULLIF(EXCLUDED.pic_url, ''), qt_like_song.pic_url),
                updated_seq = EXCLUDED.updated_seq,
                updated_at = EXCLUDED.updated_at,
                update_time = EXCLUDED.update_time
            """})
    int upsertActive(@Param("e") QtLikeSong e, @Param("seq") long seq, @Param("now") LocalDateTime now);

    /**
     * 旧全量接口封面补齐（LIKE_SONG_PIC_SYNC_DESIGN.md §5.6）：
     * 仅当库中封面为空且上传值非空时更新，不覆盖已有图片、不改变软删状态；
     * 推进 seq 让其他设备感知封面补齐（D7）。
     * <p>调用方必须保证 list 内 (sid, platform, pid) 唯一且 picUrl 非空——
     * VALUES 源行重复或全 NULL 会让 PG 的类型推断/多源行匹配产生不确定结果。</p>
     */
    @Update({"""
            <script>
            UPDATE qt_like_song AS l
            SET pic_url = v.pic_url,
                update_time = #{now},
                updated_at = #{now},
                updated_seq = #{seq}
            FROM (
                VALUES
                <foreach item='it' index='index' collection='list' separator=','>
                    (CAST(#{it.sid} AS VARCHAR), CAST(#{it.platform} AS VARCHAR),
                     CAST(#{it.pid} AS VARCHAR), CAST(#{it.picUrl} AS VARCHAR))
                </foreach>
            ) AS v(sid, platform, pid, pic_url)
            WHERE l.uid = #{uid}
              AND l.sid = v.sid
              AND l.platform = v.platform
              AND l.pid = v.pid
              AND l.deleted_at IS NULL
              AND (l.pic_url IS NULL OR l.pic_url = '')
              AND v.pic_url IS NOT NULL
              AND v.pic_url &lt;&gt; ''
            </script>
            """})
    int backfillPicUrl(@Param("list") List<QtLikeSong> list, @Param("uid") Long uid,
                       @Param("seq") long seq, @Param("now") LocalDateTime now);

    /**
     * 取消收藏单曲（带 pid）：仅软删除该歌曲在指定歌单下的收藏行，推进 seq（幂等）。
     */
    @Update("""
            UPDATE qt_like_song
            SET deleted_at = #{now}, update_time = #{now}, updated_seq = #{seq}, updated_at = #{now}
            WHERE uid = #{uid} AND sid = #{sid} AND platform = #{platform} AND pid = #{pid}
              AND deleted_at IS NULL
            """)
    int softRemoveByPlaylist(@Param("uid") Long uid, @Param("sid") String sid,
                             @Param("platform") String platform, @Param("pid") String pid,
                             @Param("seq") long seq, @Param("now") LocalDateTime now);

    /**
     * 取消收藏单曲（不带 pid）：软删除该歌曲在全部歌单下的收藏行，推进 seq（幂等）。
     */
    @Update("""
            UPDATE qt_like_song
            SET deleted_at = #{now}, update_time = #{now}, updated_seq = #{seq}, updated_at = #{now}
            WHERE uid = #{uid} AND sid = #{sid} AND platform = #{platform} AND deleted_at IS NULL
            """)
    int softRemoveAll(@Param("uid") Long uid, @Param("sid") String sid, @Param("platform") String platform,
                      @Param("seq") long seq, @Param("now") LocalDateTime now);

    /** 用户在歌曲表的最大变更序号（无记录返回 0） */
    @Select("SELECT COALESCE(MAX(updated_seq), 0) FROM qt_like_song WHERE uid = #{uid}")
    Long selectMaxSeq(@Param("uid") Long uid);

    class QtSongTuple {
        public String id;
        public String platform;
    }
}
