package com.astral.qt.service;

import com.astral.qt.dto.QtPlaylistDto;
import com.astral.qt.dto.QtSongDto;
import com.astral.qt.dto.QtLikePlaylistActionDto;
import com.astral.qt.dto.QtLikeSongActionDto;
import com.astral.qt.dto.QtUploadLikeListDto;
import com.astral.qt.dto.vo.QtLikeChangeVo;
import com.astral.qt.dto.vo.QtLikeChangesVo;
import com.astral.qt.dto.vo.QtLikeListVo;
import com.astral.qt.dto.vo.QtLikePageVo;
import com.astral.qt.dto.vo.QtLikeSeqVo;
import com.astral.qt.entity.QtLikePlaylist;
import com.astral.qt.entity.QtLikeSong;
import com.astral.qt.mapper.QtLikePlaylistMapper;
import com.astral.qt.mapper.QtLikeSongMapper;
import com.astral.qt.mapper.QtLikeSyncMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻听收藏同步服务
 * <p>旧全量同步（getLikeList/uploadLikeList）+ 新增逐条收藏与增量拉取（LIKE_SYNC_DESIGN.md）：</p>
 * <ul>
 *   <li>单条收藏/取消：action add|remove，每次操作取号一次，事务内 pg_advisory_xact_lock 串行化</li>
 *   <li>增量拉取：since 游标按 updated_seq 升序返回变更（含删除），支持多端同步</li>
 *   <li>全量分页：首次/兜底同步，附带 maxSeq 作为初始游标</li>
 *   <li>冲突策略：last-write-wins，updated_seq 较大者胜出</li>
 * </ul>
 */
@Slf4j
@Service
public class QtLikeService extends ServiceImpl<QtLikePlaylistMapper, QtLikePlaylist> {

    /** 增量拉取单页上限（防止 since=0 首拉全量过大，客户端按返回结果翻页续拉） */
    private static final int CHANGES_PAGE_LIMIT = 500;

    /** 全量分页默认/最大页大小 */
    private static final int LIKE_PAGE_SIZE_DEFAULT = 500;
    private static final int LIKE_PAGE_SIZE_MAX = 1000;

    @Resource
    private QtLikePlaylistMapper playlistMapper;

    @Resource
    private QtLikeSongMapper songMapper;

    @Resource
    private QtLikeSyncMapper likeSyncMapper;

    @Resource
    private QtSequenceService qtSequenceService;

    // ==================== 旧全量接口 ====================

    public QtLikeListVo getLikeList(Long uid) {
        List<QtLikePlaylist> playlists = playlistMapper.selectList(
                new LambdaQueryWrapper<QtLikePlaylist>()
                        .eq(QtLikePlaylist::getUid, uid)
                        .isNull(QtLikePlaylist::getDeletedAt)
        );
        List<QtLikeSong> songs = songMapper.selectList(
                new LambdaQueryWrapper<QtLikeSong>()
                        .eq(QtLikeSong::getUid, uid)
                        .isNull(QtLikeSong::getDeletedAt)
        );
        QtLikeListVo vo = new QtLikeListVo();
        vo.setPlaylist(playlists);
        vo.setSong(songs);
        return vo;
    }

    /**
     * 旧全量同步（兼容保留）：单遍 diff + batch insert + 单次取号，
     * 删除与新增各一条批量 SQL，替代逐条 selectCount/insert。
     */
    @Transactional
    public void uploadLikeList(Long uid, QtUploadLikeListDto dto) {
        if (dto == null) {
            return;
        }
        List<QtPlaylistDto> uploadPlaylists = dto.getPlaylistList() == null ? Collections.emptyList() : dto.getPlaylistList();
        List<QtSongDto> uploadSongs = dto.getSongList() == null ? Collections.emptyList() : dto.getSongList();

        // 事务内加用户级咨询锁，与单条收藏接口共用同一把锁
        likeSyncMapper.lockUser(uid);

        long nowSeq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
        LocalDateTime now = LocalDateTime.now();

        // ---------- 歌单同步 ----------
        List<QtLikePlaylist> existPlaylists = playlistMapper.selectList(
                new LambdaQueryWrapper<QtLikePlaylist>().eq(QtLikePlaylist::getUid, uid)
        );
        Set<String> uploadPlaylistKeys = new HashSet<>();
        for (QtPlaylistDto p : uploadPlaylists) {
            uploadPlaylistKeys.add(p.getId() + "@" + p.getPlatform());
        }
        // 单遍 diff：库中未删除但上传列表缺失 → 软删除
        List<QtLikePlaylistMapper.QtPlaylistTuple> delPlaylists = new ArrayList<>();
        for (QtLikePlaylist old : existPlaylists) {
            String key = old.getPid() + "@" + old.getPlatform();
            if (old.getDeletedAt() == null && !uploadPlaylistKeys.contains(key)) {
                QtLikePlaylistMapper.QtPlaylistTuple t = new QtLikePlaylistMapper.QtPlaylistTuple();
                t.id = old.getPid();
                t.platform = old.getPlatform();
                delPlaylists.add(t);
            }
        }
        if (!delPlaylists.isEmpty()) {
            playlistMapper.softDelete(delPlaylists, now, uid, nowSeq);
        }

        // 单遍 diff：上传列表中库内不存在的 → 批量插入
        Set<String> existPlaylistKeys = new HashSet<>();
        for (QtLikePlaylist old : existPlaylists) {
            existPlaylistKeys.add(old.getPid() + "@" + old.getPlatform());
        }
        List<QtLikePlaylist> newPlaylists = new ArrayList<>();
        for (QtPlaylistDto upload : uploadPlaylists) {
            if (existPlaylistKeys.contains(upload.getId() + "@" + upload.getPlatform())) {
                continue;
            }
            QtLikePlaylist p = new QtLikePlaylist();
            p.setId(qtSequenceService.nextId("qt_like_playlist"));
            p.setUid(uid);
            p.setPid(upload.getId());
            p.setPlatform(upload.getPlatform());
            p.setName(upload.getName());
            p.setPicUrl(upload.getPicUrl());
            p.setIsImport(upload.getIsImport() == null ? 0 : upload.getIsImport().intValue());
            p.setCreateTime(now);
            p.setUpdateTime(now);
            p.setUpdatedSeq(nowSeq);
            p.setUpdatedAt(now);
            newPlaylists.add(p);
        }
        if (!newPlaylists.isEmpty()) {
            playlistMapper.insertBatch(newPlaylists);
        }

        // ---------- 歌曲同步 ----------
        List<QtLikeSong> existSongs = songMapper.selectList(
                new LambdaQueryWrapper<QtLikeSong>().eq(QtLikeSong::getUid, uid)
        );
        Set<String> uploadSongKeys = new HashSet<>();
        for (QtSongDto s : uploadSongs) {
            uploadSongKeys.add(s.getId() + "@" + s.getPlatform());
        }
        List<QtLikeSongMapper.QtSongTuple> delSongs = new ArrayList<>();
        for (QtLikeSong old : existSongs) {
            String key = old.getSid() + "@" + old.getPlatform();
            if (old.getDeletedAt() == null && !uploadSongKeys.contains(key)) {
                QtLikeSongMapper.QtSongTuple t = new QtLikeSongMapper.QtSongTuple();
                t.id = old.getSid();
                t.platform = old.getPlatform();
                delSongs.add(t);
            }
        }
        if (!delSongs.isEmpty()) {
            songMapper.softDelete(delSongs, now, uid, nowSeq);
        }

        Set<String> existSongKeys = new HashSet<>();
        for (QtLikeSong old : existSongs) {
            existSongKeys.add(old.getSid() + "@" + old.getPlatform());
        }
        List<QtLikeSong> newSongs = new ArrayList<>();
        for (QtSongDto upload : uploadSongs) {
            if (existSongKeys.contains(upload.getId() + "@" + upload.getPlatform())) {
                continue;
            }
            QtLikeSong s = new QtLikeSong();
            s.setId(qtSequenceService.nextId("qt_like_song"));
            s.setUid(uid);
            s.setSid(upload.getId());
            // pid 优先（新字段），为空回退旧字段 likePlaylist（老客户端兼容）；统一归一化为空串
            s.setPid(normalizePid(upload.getPid() != null && !upload.getPid().isBlank()
                    ? upload.getPid() : upload.getLikePlaylist()));
            s.setPlatform(upload.getPlatform());
            s.setName(upload.getName());
            s.setSinger(upload.getSinger());
            s.setAlbum(upload.getAlbum());
            s.setHash(upload.getHash());
            // 封面随收藏入库（LIKE_SONG_PIC_SYNC_DESIGN.md）：新收藏落库时写入 pic_url
            s.setPicUrl(normalizePicUrl(upload.getPicUrl()));
            s.setCreateTime(now);
            s.setUpdateTime(now);
            s.setUpdatedSeq(nowSeq);
            s.setUpdatedAt(now);
            newSongs.add(s);
        }
        if (!newSongs.isEmpty()) {
            songMapper.insertBatch(newSongs);
        }

        // ---------- 旧客户端封面补齐（LIKE_SONG_PIC_SYNC_DESIGN.md §5.6） ----------
        // PC/旧客户端全量上传现在会携带 picUrl：仅补齐库中封面为空的行，绝不覆盖已有封面（D4）。
        // 库中已有封面的行交给 upsert 场景，全量接口不做无条件覆盖，避免把云端有效图抹掉。
        backfillSongCovers(uid, uploadSongs, existSongs, nowSeq, now);
    }

    /**
     * 旧全量接口封面补齐：把上传列表里非空的 picUrl 补到「库中存在、未删除且封面为空」的行上。
     * <p>按 (sid, platform, pid) 匹配（与唯一键 uk_like_song_key_pid 对齐），
     * 同一 key 去重后整批一次 UPDATE，推进 seq 让其他设备感知封面补齐（D7）。</p>
     */
    private void backfillSongCovers(Long uid, List<QtSongDto> uploadSongs, List<QtLikeSong> existSongs,
                                    long seq, LocalDateTime now) {
        if (uploadSongs.isEmpty()) {
            return;
        }
        // 上传侧：只收 picUrl 非空的项，key 去重（同一歌曲可能在上传列表里重复出现）
        Map<String, QtLikeSong> uploadedWithCover = new LinkedHashMap<>();
        for (QtSongDto upload : uploadSongs) {
            String pic = normalizePicUrl(upload.getPicUrl());
            if (pic == null) {
                continue;
            }
            String pid = normalizePid(upload.getPid() != null && !upload.getPid().isBlank()
                    ? upload.getPid() : upload.getLikePlaylist());
            QtLikeSong row = new QtLikeSong();
            row.setSid(upload.getId());
            row.setPlatform(upload.getPlatform());
            row.setPid(pid);
            row.setPicUrl(pic);
            uploadedWithCover.putIfAbsent(upload.getId() + "@" + upload.getPlatform() + "@" + pid, row);
        }
        if (uploadedWithCover.isEmpty()) {
            return;
        }
        // 库侧：只补「未删除且封面为空」的行
        Set<String> blankCoverKeys = new HashSet<>();
        for (QtLikeSong old : existSongs) {
            if (old.getDeletedAt() == null && normalizePicUrl(old.getPicUrl()) == null) {
                blankCoverKeys.add(old.getSid() + "@" + old.getPlatform() + "@" + old.getPid());
            }
        }
        if (blankCoverKeys.isEmpty()) {
            return;
        }
        List<QtLikeSong> backfillRows = new ArrayList<>();
        for (Map.Entry<String, QtLikeSong> entry : uploadedWithCover.entrySet()) {
            if (blankCoverKeys.contains(entry.getKey())) {
                backfillRows.add(entry.getValue());
            }
        }
        if (!backfillRows.isEmpty()) {
            songMapper.backfillPicUrl(backfillRows, uid, seq, now);
        }
    }

    // ==================== 新接口：单条收藏/取消 ====================

    /** 收藏/取消收藏单曲（LIKE_SYNC_DESIGN.md §2.1），返回本次 seq */
    @Transactional
    public QtLikeSeqVo likeSong(Long uid, QtLikeSongActionDto dto) {
        likeSyncMapper.lockUser(uid);
        long seq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
        LocalDateTime now = LocalDateTime.now();
        if ("add".equals(dto.getAction())) {
            QtLikeSong e = new QtLikeSong();
            e.setId(qtSequenceService.nextId("qt_like_song"));
            e.setUid(uid);
            e.setSid(dto.getSid());
            e.setPid(normalizePid(dto.getPid()));
            e.setPlatform(dto.getPlatform());
            e.setName(dto.getName());
            e.setSinger(dto.getSinger());
            e.setAlbum(dto.getAlbum());
            e.setHash(dto.getHash());
            // 封面随收藏入库（LIKE_SONG_PIC_SYNC_DESIGN.md D3/D4）：仅 add 生效，空值归一化为 null
            e.setPicUrl(normalizePicUrl(dto.getPicUrl()));
            songMapper.upsertActive(e, seq, now);
        } else {
            // 带 pid：仅从该歌单移除；不带 pid：该歌曲在全部歌单的收藏一并移除
            String pid = normalizePid(dto.getPid());
            if (pid.isEmpty()) {
                songMapper.softRemoveAll(uid, dto.getSid(), dto.getPlatform(), seq, now);
            } else {
                songMapper.softRemoveByPlaylist(uid, dto.getSid(), dto.getPlatform(), pid, seq, now);
            }
        }
        return QtLikeSeqVo.of(seq);
    }

    /** pid 归一化：null/空白 → 空串（表示不归属具体歌单），其余去首尾空白 */
    private String normalizePid(String pid) {
        return pid == null ? "" : pid.trim();
    }

    /**
     * picUrl 归一化（LIKE_SONG_PIC_SYNC_DESIGN.md D8）：null/空白 → null，其余去首尾空白。
     * <p>统一归一化为 null 而非空串，保证 SQL 里 COALESCE(NULLIF(...)) 能正确区分
     * 「本次没带图」和「带了有效图」，避免旧客户端用空值覆盖云端已有封面。</p>
     */
    private String normalizePicUrl(String picUrl) {
        if (picUrl == null || picUrl.isBlank()) {
            return null;
        }
        return picUrl.trim();
    }

    /** 收藏/取消收藏歌单（LIKE_SYNC_DESIGN.md §2.2），返回本次 seq */
    @Transactional
    public QtLikeSeqVo likePlaylist(Long uid, QtLikePlaylistActionDto dto) {
        likeSyncMapper.lockUser(uid);
        long seq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
        LocalDateTime now = LocalDateTime.now();
        if ("add".equals(dto.getAction())) {
            QtLikePlaylist e = new QtLikePlaylist();
            e.setId(qtSequenceService.nextId("qt_like_playlist"));
            e.setUid(uid);
            e.setPid(dto.getPid());
            e.setPlatform(dto.getPlatform());
            e.setName(dto.getName());
            e.setPicUrl(dto.getPicUrl());
            e.setIsImport(0);
            playlistMapper.upsertActive(e, seq, now);
        } else {
            playlistMapper.softRemove(uid, dto.getPid(), dto.getPlatform(), seq, now);
        }
        return QtLikeSeqVo.of(seq);
    }

    // ==================== 新接口：增量拉取 ====================

    /**
     * 增量拉取 since 之后的变更（LIKE_SYNC_DESIGN.md §2.3）。
     * <p>超过单页上限时截断，客户端以最后一条 updated_seq 续拉；maxSeq 为当前用户全量最大值。</p>
     */
    public QtLikeChangesVo getChanges(Long uid, long since) {
        long maxSeq = likeSyncMapper.selectUserMaxSeq(uid);
        List<QtLikeChangeVo> changes = likeSyncMapper.selectChanges(uid, since, CHANGES_PAGE_LIMIT, 0);
        QtLikeChangesVo vo = new QtLikeChangesVo();
        vo.setChanges(changes);
        vo.setMaxSeq(maxSeq);
        return vo;
    }

    // ==================== 新接口：全量分页 ====================

    /** 全量分页拉取未删除收藏（LIKE_SYNC_DESIGN.md §2.4），附带 maxSeq 作为初始游标 */
    public QtLikePageVo getLikePage(Long uid, Integer page, Integer size) {
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? LIKE_PAGE_SIZE_DEFAULT : Math.min(size, LIKE_PAGE_SIZE_MAX);
        long offset = (long) (pageNo - 1) * pageSize;

        List<QtLikeSong> songs = songMapper.selectList(
                new LambdaQueryWrapper<QtLikeSong>()
                        .eq(QtLikeSong::getUid, uid)
                        .isNull(QtLikeSong::getDeletedAt)
                        .orderByDesc(QtLikeSong::getUpdatedSeq)
                        .last("LIMIT " + pageSize + " OFFSET " + offset)
        );
        List<QtLikePlaylist> playlists = playlistMapper.selectList(
                new LambdaQueryWrapper<QtLikePlaylist>()
                        .eq(QtLikePlaylist::getUid, uid)
                        .isNull(QtLikePlaylist::getDeletedAt)
                        .orderByDesc(QtLikePlaylist::getUpdatedSeq)
                        .last("LIMIT " + pageSize + " OFFSET " + offset)
        );
        QtLikePageVo vo = new QtLikePageVo();
        vo.setSongs(songs);
        vo.setPlaylists(playlists);
        vo.setMaxSeq(likeSyncMapper.selectUserMaxSeq(uid));
        return vo;
    }
}
