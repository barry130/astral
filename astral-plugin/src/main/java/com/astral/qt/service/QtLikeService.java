package com.astral.qt.service;

import com.astral.qt.common.QtException;
import com.astral.qt.dto.QtLikeBatchDto;
import com.astral.qt.dto.QtLikeBatchOpDto;
import com.astral.qt.dto.QtLikePlaylistActionDto;
import com.astral.qt.dto.QtLikeSongActionDto;
import com.astral.qt.dto.vo.QtLikeChangeVo;
import com.astral.qt.dto.vo.QtLikeChangesVo;
import com.astral.qt.dto.vo.QtLikePageVo;
import com.astral.qt.dto.vo.QtLikeSeqVo;
import com.astral.qt.entity.QtLikePlaylist;
import com.astral.qt.entity.QtLikeSong;
import com.astral.qt.mapper.QtLikePlaylistMapper;
import com.astral.qt.mapper.QtLikeSongMapper;
import com.astral.qt.mapper.QtLikeSyncMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻听收藏同步服务
 * <p>逐条收藏与增量拉取（LIKE_SYNC_DESIGN.md）；旧全量接口 getLikeList/uploadLikeList 已删除：</p>
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

    // ==================== 单条收藏/取消 ====================

    /** 收藏/取消收藏单曲（LIKE_SYNC_DESIGN.md §2.1），返回本次 seq */
    @Transactional(rollbackFor = Exception.class)
    public QtLikeSeqVo likeSong(Long uid, QtLikeSongActionDto dto) {
        likeSyncMapper.lockUser(uid);
        long seq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
        LocalDateTime now = LocalDateTime.now();
        if ("add".equals(dto.getAction())) {
            QtLikeSong e = new QtLikeSong();
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

    /**
     * 收藏/取消收藏歌单（LIKE_SYNC_DESIGN.md §2.2），返回本次 seq。
     * <p>remove 时级联硬删该歌单下的全部成员歌曲行（uid + pid 定位）：
     * 客户端删除歌单只推 playlist remove，成员 (sid,pid) 行由服务端一并清理；
     * 歌单 remove 事件随 changes 下发，客户端据此清理本地成员。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public QtLikeSeqVo likePlaylist(Long uid, QtLikePlaylistActionDto dto) {
        likeSyncMapper.lockUser(uid);
        long seq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
        LocalDateTime now = LocalDateTime.now();
        if ("add".equals(dto.getAction())) {
            QtLikePlaylist e = new QtLikePlaylist();
            e.setUid(uid);
            e.setPid(dto.getPid());
            e.setPlatform(dto.getPlatform());
            e.setName(dto.getName());
            e.setPicUrl(dto.getPicUrl());
            e.setIsImport(0);
            playlistMapper.upsertActive(e, seq, now);
        } else {
            playlistMapper.softRemove(uid, dto.getPid(), dto.getPlatform(), seq, now);
            // 级联软删该歌单全部成员歌曲行：客户端删除歌单只推 playlist remove，
            // 成员 (sid,pid) 行由服务端一并清理；成员行同样推进 seq，其他端经 changes 感知摘 pid
            songMapper.softRemoveAllByPlaylist(uid, dto.getPid(), seq, now);
        }
        return QtLikeSeqVo.of(seq);
    }

    // ==================== 新接口：批量收藏（LIKE_SYNC_DESIGN.md §2.5） ====================

    /** 批量操作的分段类型：同型连续段各自合成一条批量 SQL，段间按序执行保住原始顺序语义 */
    private enum BatchRunKind {
        SONG_UPSERT, SONG_RM_PID, SONG_RM_ALL, PLAYLIST_UPSERT, PLAYLIST_RM
    }

    /** 一个连续同型段 */
    private static class BatchRun {
        final BatchRunKind kind;
        final List<QtLikeBatchOpDto> ops = new ArrayList<>();

        BatchRun(BatchRunKind kind) {
            this.kind = kind;
        }
    }

    /**
     * 批量收藏/取消（歌曲+歌单混排，单批 ≤200），供客户端离线队列/批量导入一次提交。
     * <p>与单条接口同语义：事务内用户级咨询锁串行化、seq 用户维度单调递增、last-write-wins。
     * seq 按数组顺序逐条递增（批内后写必胜，changes 流保持单调）；整批一个事务，
     * 返回整批最大 seq——本批占用连续区段 [base+1, seq]，其他端变更必然更大，
     * 客户端把同步游标直接推进到该值是安全的。</p>
     * <p>弱 DB 往返优化：①取锁+取号合并一条 SQL；②按数组原始顺序把连续同型段各自合成
     * 一条批量 SQL——段间顺序执行保住「playlist remove 级联软删 vs song add」的先后语义，
     * 段内按自然键去重保最后一条（规避 PG 同语句同键冲突）。
     * 典型导入（1 歌单 add + N 首 add）整批只需 3 次 DB 往返：取号 + 歌单 upsert + 歌曲 upsert。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public QtLikeSeqVo likeBatch(Long uid, QtLikeBatchDto dto) {
        List<QtLikeBatchOpDto> ops = dto == null || dto.getOps() == null ? Collections.emptyList() : dto.getOps();
        if (ops.isEmpty()) {
            return QtLikeSeqVo.of(likeSyncMapper.selectUserMaxSeq(uid));
        }
        for (QtLikeBatchOpDto op : ops) {
            validateBatchOp(op);
        }
        long baseSeq = likeSyncMapper.lockAndMaxSeq(uid);

        // 按原始顺序编号 + 连续同型分段
        long seq = baseSeq;
        List<BatchRun> runs = new ArrayList<>();
        for (QtLikeBatchOpDto op : ops) {
            seq++;
            op.setSeq(seq);
            BatchRunKind kind = classifyBatchOp(op);
            if (!runs.isEmpty()) {
                BatchRun last = runs.get(runs.size() - 1);
                if (last.kind == kind) {
                    last.ops.add(op);
                    continue;
                }
            }
            BatchRun run = new BatchRun(kind);
            run.ops.add(op);
            runs.add(run);
        }
        for (BatchRun run : runs) {
            executeBatchRun(uid, run);
        }
        return QtLikeSeqVo.of(seq);
    }

    /** 条件必填校验（扁平 DTO 无法按 type 用 Bean Validation 标注），违规抛 320 */
    private void validateBatchOp(QtLikeBatchOpDto op) {
        if (isBlank(op.getType())) {
            throw new QtException(320, "type不能为空");
        }
        if (isBlank(op.getAction())) {
            throw new QtException(320, "action不能为空");
        }
        if ("song".equals(op.getType())) {
            if (isBlank(op.getSid()) || isBlank(op.getPlatform())) {
                throw new QtException(320, "song操作必须携带sid和platform");
            }
        } else if (isBlank(op.getPid()) || isBlank(op.getPlatform())) {
            throw new QtException(320, "playlist操作必须携带pid和platform");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private BatchRunKind classifyBatchOp(QtLikeBatchOpDto op) {
        boolean add = "add".equals(op.getAction());
        if ("song".equals(op.getType())) {
            if (add) {
                return BatchRunKind.SONG_UPSERT;
            }
            // remove 不带 pid = 从全部歌单移除，SQL 形态不同，单独成段
            return normalizePid(op.getPid()).isEmpty() ? BatchRunKind.SONG_RM_ALL : BatchRunKind.SONG_RM_PID;
        }
        return add ? BatchRunKind.PLAYLIST_UPSERT : BatchRunKind.PLAYLIST_RM;
    }

    /** 段内去重的自然键（同键保留最后一条，其 seq 最大=最终态） */
    private String batchOpKey(BatchRunKind kind, QtLikeBatchOpDto op) {
        return switch (kind) {
            case SONG_UPSERT, SONG_RM_PID -> "s@" + op.getSid() + "@" + op.getPlatform() + "@" + normalizePid(op.getPid());
            case SONG_RM_ALL -> "a@" + op.getSid() + "@" + op.getPlatform();
            case PLAYLIST_UPSERT, PLAYLIST_RM -> "p@" + op.getPid() + "@" + op.getPlatform();
        };
    }

    /** 执行一个同型段：段内按自然键去重后走对应批量 SQL */
    private void executeBatchRun(Long uid, BatchRun run) {
        Map<String, QtLikeBatchOpDto> dedup = new LinkedHashMap<>();
        for (QtLikeBatchOpDto op : run.ops) {
            dedup.put(batchOpKey(run.kind, op), op);
        }
        List<QtLikeBatchOpDto> items = new ArrayList<>(dedup.values());
        switch (run.kind) {
            case SONG_UPSERT -> {
                List<QtLikeSong> rows = new ArrayList<>(items.size());
                for (QtLikeBatchOpDto op : items) {
                    rows.add(buildBatchSong(uid, op));
                }
                songMapper.upsertActiveBatch(rows);
            }
            case SONG_RM_PID -> songMapper.softRemoveByPlaylistBatch(buildBatchSongRows(uid, items), uid, LocalDateTime.now());
            case SONG_RM_ALL -> songMapper.softRemoveAllBatch(buildBatchSongRows(uid, items), uid, LocalDateTime.now());
            case PLAYLIST_UPSERT -> {
                List<QtLikePlaylist> rows = new ArrayList<>(items.size());
                for (QtLikeBatchOpDto op : items) {
                    rows.add(buildBatchPlaylist(uid, op));
                }
                playlistMapper.upsertActiveBatch(rows);
            }
            case PLAYLIST_RM -> {
                List<QtLikePlaylist> rows = new ArrayList<>(items.size());
                for (QtLikeBatchOpDto op : items) {
                    rows.add(buildBatchPlaylistRow(uid, op));
                }
                playlistMapper.softRemoveBatch(rows, uid, LocalDateTime.now());
                // 级联软删成员歌曲行（与单条 remove 一致），成员行带同一 seq 供其他端感知摘 pid
                songMapper.softRemoveAllByPlaylistBatch(rows, uid, LocalDateTime.now());
            }
        }
    }

    /** song upsert 行：字段与单条 likeSong add 分支一致（picUrl 归一化，id 由号段填充器补） */
    private QtLikeSong buildBatchSong(Long uid, QtLikeBatchOpDto op) {
        LocalDateTime now = LocalDateTime.now();
        QtLikeSong e = new QtLikeSong();
        e.setUid(uid);
        e.setSid(op.getSid());
        e.setPid(normalizePid(op.getPid()));
        e.setPlatform(op.getPlatform());
        e.setName(op.getName());
        e.setSinger(op.getSinger());
        e.setAlbum(op.getAlbum());
        e.setHash(op.getHash());
        e.setPicUrl(normalizePicUrl(op.getPicUrl()));
        e.setCreateTime(now);
        e.setUpdateTime(now);
        e.setUpdatedSeq(op.getSeq());
        e.setUpdatedAt(now);
        return e;
    }

    /** song 软删行的载体：只需要 sid/platform/pid/updatedSeq 四个字段 */
    private List<QtLikeSong> buildBatchSongRows(Long uid, List<QtLikeBatchOpDto> items) {
        List<QtLikeSong> rows = new ArrayList<>(items.size());
        for (QtLikeBatchOpDto op : items) {
            QtLikeSong e = new QtLikeSong();
            e.setUid(uid);
            e.setSid(op.getSid());
            e.setPid(normalizePid(op.getPid()));
            e.setPlatform(op.getPlatform());
            e.setUpdatedSeq(op.getSeq());
            rows.add(e);
        }
        return rows;
    }

    /** playlist upsert 行：字段与单条 likePlaylist add 分支一致 */
    private QtLikePlaylist buildBatchPlaylist(Long uid, QtLikeBatchOpDto op) {
        LocalDateTime now = LocalDateTime.now();
        QtLikePlaylist e = new QtLikePlaylist();
        e.setUid(uid);
        e.setPid(op.getPid());
        e.setPlatform(op.getPlatform());
        e.setName(op.getName());
        e.setPicUrl(op.getPicUrl());
        e.setIsImport(0);
        e.setCreateTime(now);
        e.setUpdateTime(now);
        e.setUpdatedSeq(op.getSeq());
        e.setUpdatedAt(now);
        return e;
    }

    /** playlist 软删行的载体：只需要 pid/platform/updatedSeq */
    private QtLikePlaylist buildBatchPlaylistRow(Long uid, QtLikeBatchOpDto op) {
        QtLikePlaylist e = new QtLikePlaylist();
        e.setUid(uid);
        e.setPid(op.getPid());
        e.setPlatform(op.getPlatform());
        e.setUpdatedSeq(op.getSeq());
        return e;
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
