package com.astral.qt.service;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.common.QtException;
import com.astral.qt.dto.vo.QtUploadCompleteVo;
import com.astral.qt.dto.vo.QtUploadTicketVo;
import com.astral.qt.entity.QtLikePlaylist;
import com.astral.qt.mapper.QtLikePlaylistMapper;
import com.astral.qt.mapper.QtLikeSyncMapper;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.service.StorageConfigService;
import com.astral.storage.service.StorageFileService;
import com.astral.storage.service.UploadPolicyService;
import com.astral.storage.security.UploadTicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qt 媒体上传服务（UPDATE_DESIGN.md §5/§6）：头像与歌单封面的直传编排层。
 * <p>职责边界（设计文档 §3）：本服务只是 storage 插件的调用方——鉴权、业务归属判断、
 * 调用插件签发凭证/登记、把返回的稳定 URL 写入业务字段、触发旧文件回收；
 * <b>全部文件校验（登录/类型/大小/每日频控/内容验证）由 storage 插件完成</b>，
 * 文件字节不经过 Astral 服务器（客户端直传 worker/对象存储）。</p>
 * <p>业务字段直存（不新建表）：头像 → {@code sys_user.avatar}；
 * 歌单封面 → {@code qt_like_playlist.pic_url}（唯一键 uid+pid+platform，覆盖式自定义）。</p>
 */
@Slf4j
@Service
public class QtMediaService {

    /** 约定文件夹（默认仓库下的展示路径） */
    public static final String FOLDER_ROOT = "qt-media";
    public static final String FOLDER_AVATAR = "avatar";
    public static final String FOLDER_COVER = "playlist-cover";

    /** 对客稳定业务 URL 长度守卫：两个字段均为 VARCHAR(256)（UPDATE_DESIGN.md §3.2） */
    private static final int MAX_URL_LENGTH = 256;
    /** TELEGRAM 永久链形态：{worker}/p/{publicId}/{contentVersion}，旧文件回收用 */
    private static final Pattern TELEGRAM_PERMANENT_URL = Pattern.compile("/p/([0-9a-fA-F]{16,64})/\\d+$");

    @Resource
    private StorageConfigService storageConfigService;

    @Resource
    private StorageFolderMapper storageFolderMapper;

    @Resource
    private UploadTicketService uploadTicketService;

    @Resource
    private StorageFileService storageFileService;

    @Resource
    private UploadPolicyService uploadPolicyService;

    @Resource
    private PermissionChecker permissionChecker;

    @Resource
    private UserMapper userMapper;

    @Resource
    private QtLikePlaylistMapper playlistMapper;

    @Resource
    private QtLikeSyncMapper likeSyncMapper;

    /** 仅包住 seq 推进 + pic_url 直写的短事务（登记/校验在事务外，见 coverComplete 注释） */
    @Resource
    private TransactionTemplate transactionTemplate;

    // ==================== 头像（仅修改时上传，每日配额由文件夹策略承载） ====================

    /** 头像上传取凭证：文件夹固定 qt-media/avatar，客户端不可选 */
    public QtUploadTicketVo avatarTicket(Long uid, String fileName, String contentType, long sizeBytes) {
        StorageFolderEntity folder = ensureMediaTree(FOLDER_AVATAR);
        return issueTicket(folder, uid, fileName, contentType, sizeBytes);
    }

    /**
     * 头像直传完成：登记核对（uploader/文件夹）→ 永久 URL → 长度守卫 →
     * 单事务直写 {@code sys_user.avatar}（不踢下线，与改资料接口语义区分）→ 异步删旧文件。
     */
    public QtUploadCompleteVo avatarComplete(Long uid, String uploadId, HttpServletRequest request) {
        StorageFolderEntity folder = ensureMediaTree(FOLDER_AVATAR);
        StorageFileEntity file = registerAndGet(uploadId, uid.toString(), request, folder);
        String url = permanentUrlOf(file, uid);
        User user = userMapper.selectById(uid);
        if (user == null) {
            throw new QtException(401, "登录状态已失效");
        }
        String oldAvatar = user.getAvatar();
        user.setAvatar(url);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        recycleOldFile(oldAvatar, uid);
        log.info("[QtPlugin] 头像更新: uid={}, url={}", uid, url);
        return new QtUploadCompleteVo(url);
    }

    // ==================== 歌单封面（仅修改时上传；每日配额按文件夹聚合=全部歌单合计） ====================

    /** 歌单封面上传取凭证：先做业务归属校验（只能改自己收藏行的封面） */
    public QtUploadTicketVo coverTicket(Long uid, String pid, String platform,
                                        String fileName, String contentType, long sizeBytes) {
        requireOwnPlaylist(uid, pid, platform);
        StorageFolderEntity folder = ensureMediaTree(FOLDER_COVER);
        return issueTicket(folder, uid, fileName, contentType, sizeBytes);
    }

    /**
     * 歌单封面直传完成：登记核对 → 永久 URL → 长度守卫 → 推进同步 seq 直写 {@code pic_url} → 删旧文件。
     * <p>登记与内容校验刻意放在业务事务之外：verifyContent 失败要**留下 FAILED 登记行**
     * （每日配额按非 FAILED 计数依赖该行存在），整体回滚会把行吞掉；
     * 业务事务只包 seq 推进 + pic_url 直写（advisory lock 需要事务内执行）。</p>
     */
    public QtUploadCompleteVo coverComplete(Long uid, String pid, String platform,
                                            String uploadId, HttpServletRequest request) {
        StorageFolderEntity folder = ensureMediaTree(FOLDER_COVER);
        StorageFileEntity file = registerAndGet(uploadId, uid.toString(), request, folder);
        String url = permanentUrlOf(file, uid);
        QtLikePlaylist row = requireOwnPlaylist(uid, pid, platform);
        String oldCover = row.getPicUrl();
        transactionTemplate.executeWithoutResult(status -> {
            likeSyncMapper.lockUser(uid);
            long seq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
            LocalDateTime now = LocalDateTime.now();
            row.setPicUrl(url);
            row.setUpdatedSeq(seq);
            row.setUpdatedAt(now);
            row.setUpdateTime(now);
            playlistMapper.updateById(row);
        });
        recycleOldFile(oldCover, uid);
        log.info("[QtPlugin] 歌单封面更新: uid={}, pid={}, platform={}, url={}", uid, pid, platform, url);
        return new QtUploadCompleteVo(url);
    }

    /** 取消自定义封面（恢复默认）：pic_url 置空，客户端渲染本地默认资源；不消耗配额 */
    @Transactional(rollbackFor = Exception.class)
    public void coverClear(Long uid, String pid, String platform) {
        likeSyncMapper.lockUser(uid);
        long seq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
        LocalDateTime now = LocalDateTime.now();
        QtLikePlaylist row = requireOwnPlaylist(uid, pid, platform);
        row.setPicUrl(null);
        row.setUpdatedSeq(seq);
        row.setUpdatedAt(now);
        row.setUpdateTime(now);
        playlistMapper.updateById(row);
        log.info("[QtPlugin] 歌单封面清除: uid={}, pid={}, platform={}", uid, pid, platform);
    }

    // ==================== 内部编排 ====================

    /** 签发直传凭证（管理员豁免判定在此处，策略校验在插件 UploadTicketService） */
    private QtUploadTicketVo issueTicket(StorageFolderEntity folder, Long uid,
                                         String fileName, String contentType, long sizeBytes) {
        StorageConfigEntity config = storageConfigService.getById(folder.getStorageConfigId());
        if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new QtException("存储配置不可用，请稍后再试");
        }
        boolean adminExempt = permissionChecker.hasPermission(PermissionChecker.SUPER_PERMISSION);
        UploadTicketService.IssuedTicket ticket = uploadTicketService.issue(
                config, folder, uid.toString(), fileName, contentType, sizeBytes, adminExempt);
        return QtUploadTicketVo.of(ticket);
    }

    /**
     * complete 阶段的登记与核对：
     * 对象存储家族先尝试登记（幂等，HEAD 确认）；TELEGRAM 由 Worker 回调登记，未回执时报上传未完成；
     * 内容验证（verifyContent）幂等重跑；最后核对 uploader 与文件夹，防跨用户/跨文件夹张冠李戴。
     */
    private StorageFileEntity registerAndGet(String uploadId, String uploaderId,
                                             HttpServletRequest request, StorageFolderEntity expectedFolder) {
        try {
            storageFileService.registerFromBrowser(uploadId, uploaderId, clientIp(request));
        } catch (BusinessException e) {
            // TELEGRAM 走 Worker 回调登记：尚未回执时明确报「上传未完成」，其余错误原样抛出
            if (!"STORAGE019".equals(e.getErrorCode())) {
                throw e;
            }
        }
        storageFileService.verifyContentAfterRegistration(
                storageFileService.getByUploadId(uploadId, uploaderId).getPublicId());
        StorageFileEntity file = storageFileService.getByUploadId(uploadId, uploaderId);
        if (!expectedFolder.getId().equals(file.getFolderId())
                || !uploaderId.equals(file.getUploaderId())) {
            throw new QtException(403, "上传归属校验失败");
        }
        if (!StorageFileEntity.STATUS_AVAILABLE.equals(file.getStatus())) {
            throw new QtException("文件未完成上传或未通过校验");
        }
        return file;
    }

    /** 永久 URL 签发 + 长度守卫（业务字段 VARCHAR(256) 容量约束，业务层唯一校验） */
    private String permanentUrlOf(StorageFileEntity file, Long uid) {
        String url = storageFileService.issuePermanentUrl(file.getPublicId(), uid.toString()).url();
        if (url == null || url.length() > MAX_URL_LENGTH) {
            throw new QtException("图片地址超长，请缩短存储配置的公开访问域名");
        }
        return url;
    }

    /** 业务归属：该 (uid, pid, platform) 收藏行必须存在且未删除 */
    private QtLikePlaylist requireOwnPlaylist(Long uid, String pid, String platform) {
        QtLikePlaylist row = playlistMapper.selectOne(new LambdaQueryWrapper<QtLikePlaylist>()
                .eq(QtLikePlaylist::getUid, uid)
                .eq(QtLikePlaylist::getPid, pid)
                .eq(QtLikePlaylist::getPlatform, platform)
                .isNull(QtLikePlaylist::getDeletedAt)
                .last("LIMIT 1"));
        if (row == null) {
            throw new QtException("歌单不存在或已取消收藏");
        }
        return row;
    }

    /**
     * 约定文件夹幂等解析（UPDATE_DESIGN.md §3.1）：默认仓库下 qt-media/{avatar|playlist-cover}，
     * 不存在则创建一次（ownerType=PLUGIN），策略随创建写入；并发重复创建由唯一性兜底后回查。
     */
    private synchronized StorageFolderEntity ensureMediaTree(String leafName) {
        StorageConfigEntity config = storageConfigService.requireDefaultEnabled();
        StorageFolderEntity root = findFolder(config.getId(), FOLDER_ROOT, null);
        if (root == null) {
            root = createFolder(config, FOLDER_ROOT, null, null);
        }
        StorageFolderEntity leaf = findFolder(config.getId(), leafName, root.getId());
        if (leaf == null) {
            leaf = createFolder(config, leafName, root, leafPolicy());
        }
        if (!StorageFolderEntity.STATUS_ENABLED.equals(leaf.getStatus())) {
            throw new QtException("存储文件夹已停用，请联系管理员");
        }
        return leaf;
    }

    private StorageFolderEntity findFolder(Long configId, String folderName, Long parentId) {
        // parent_id 为 NULL 的判断必须走 isNull：eq(column, null) 生成 "parent_id = NULL" 永远不匹配，
        // 会让根文件夹每次调用都被重复创建（幂等性破坏）
        LambdaQueryWrapper<StorageFolderEntity> qw = new LambdaQueryWrapper<StorageFolderEntity>()
                .eq(StorageFolderEntity::getStorageConfigId, configId)
                .eq(StorageFolderEntity::getFolderName, folderName);
        if (parentId == null) {
            qw.isNull(StorageFolderEntity::getParentId);
        } else {
            qw.eq(StorageFolderEntity::getParentId, parentId);
        }
        return storageFolderMapper.selectOne(qw.last("LIMIT 1"));
    }

    private StorageFolderEntity createFolder(StorageConfigEntity config, String folderName,
                                             StorageFolderEntity parent, String policyJson) {
        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setParentId(parent == null ? null : parent.getId());
        folder.setFolderName(folderName);
        folder.setFolderPath(parent == null ? "/" + folderName : parent.getFolderPath() + "/" + folderName);
        folder.setStorageConfigId(config.getId());
        folder.setOwnerType(StorageFolderEntity.OWNER_ADMIN);
        folder.setOwnerId("qt");
        folder.setVisibility(StorageFolderEntity.VISIBILITY_PUBLIC);
        folder.setStatus(StorageFolderEntity.STATUS_ENABLED);
        folder.setUploadPolicy(policyJson);
        folder.setCreateBy("qt");
        folder.setUpdateBy("qt");
        try {
            storageFolderMapper.insert(folder);
            log.info("[QtPlugin] 媒体文件夹已创建: {}", folder.getFolderPath());
        } catch (Exception e) {
            // 并发创建竞态：回查已有行
            StorageFolderEntity existing = findFolder(config.getId(), folderName,
                    parent == null ? null : parent.getId());
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        return folder;
    }

    /**
     * qt 媒体文件夹策略（UPDATE_DESIGN.md §3.1/§5.4）：要求登录、单文件 ≤2MB、
     * png/jpeg/webp、每日每用户 2 次、强制 PUBLIC、登记后完整内容验证。
     */
    private String leafPolicy() {
        Map<String, Object> policy = Map.of(
                "requireLogin", true,
                "maxSizeBytes", 2L * 1024 * 1024,
                "allowedMimes", List.of("image/png", "image/jpeg", "image/webp"),
                "allowedExtensions", List.of("png", "jpg", "jpeg", "webp"),
                "dailyUploadLimit", 2,
                "forceVisibility", "PUBLIC",
                "verifyContent", UploadPolicyService.VERIFY_FULL);
        return uploadPolicyService.normalize(policy);
    }

    /**
     * 旧文件回收（UPDATE_DESIGN.md §11）：业务字段已被新 URL 覆盖后，尽力删除旧 storage 文件。
     * 仅识别 TELEGRAM 永久链形态（可提取 publicId）；旧 /files/qt-upload 本地文件与
     * 对象存储公开 URL 不在此回收（本地目录按容量策略清理，公开 URL 无 publicId 可查）。
     */
    private void recycleOldFile(String oldUrl, Long uid) {
        if (oldUrl == null || oldUrl.isBlank()) {
            return;
        }
        Matcher matcher = TELEGRAM_PERMANENT_URL.matcher(oldUrl);
        if (!matcher.find()) {
            return;
        }
        String publicId = matcher.group(1);
        try {
            storageFileService.requestDelete(publicId, uid.toString());
            log.info("[QtPlugin] 旧媒体文件已回收: {}", publicId);
        } catch (Exception e) {
            // 回收失败只产生待清理孤儿，不影响业务字段正确性
            log.info("[QtPlugin] 旧媒体文件回收跳过: publicId={}, {}", publicId, e.getMessage());
        }
    }

    /** 登记来源 IP（X-Forwarded-For 第一段 → X-Real-IP → remoteAddr，项目既有取法） */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
