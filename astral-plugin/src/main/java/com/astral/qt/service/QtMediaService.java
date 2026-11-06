package com.astral.qt.service;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.ClientIp;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.common.QtException;
import com.astral.qt.dto.vo.QtProxyContentVo;
import com.astral.qt.dto.vo.QtUploadCompleteVo;
import com.astral.qt.dto.vo.QtUploadPlanVo;
import com.astral.qt.dto.vo.QtUploadTicketVo;
import com.astral.qt.entity.QtLikePlaylist;
import com.astral.qt.mapper.QtLikePlaylistMapper;
import com.astral.qt.mapper.QtLikeSyncMapper;
import com.astral.storage.dto.UploadOperationDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.security.UploadTicketService;
import com.astral.storage.service.HttpUploadGateway;
import com.astral.storage.service.StorageConfigService;
import com.astral.storage.service.StorageFileService;
import com.astral.storage.service.StorageTicketTargetService;
import com.astral.storage.service.UploadOperationService;
import com.astral.storage.service.UploadPolicyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** 可信代理列表（决定能否采信 X-Forwarded-For），与全站口径一致 */
    @Value("${astral.web.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;

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
    private StorageFolderConfigMapper storageFolderConfigMapper;

    @Resource
    private StorageTicketTargetService storageTicketTargetService;

    @Resource
    private UploadTicketService uploadTicketService;

    @Resource
    private StorageFileService storageFileService;

    @Resource
    private UploadOperationService uploadOperationService;

    @Resource
    private StorageFileMapper storageFileMapper;

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
     * 头像上传计划（直传/两段式代传统一入口，CUSTOM_HTTP 渠道支持）：目录首选渠道为
     * 直传家族时返回直传凭证（三段式行为不变）；首选渠道为 CUSTOM_HTTP 时返回两段式
     * 代传参数（idempotencyKey 由客户端生成、重试复用同一键）。
     */
    public QtUploadPlanVo avatarUploadPlan(
            Long uid, String fileName, String contentType, long sizeBytes, String idempotencyKey) {
        StorageFolderEntity folder = ensureMediaTree(FOLDER_AVATAR);
        return mediaUploadPlan(folder, uid, fileName, contentType, sizeBytes, idempotencyKey);
    }

    /**
     * 歌单封面上传计划：先做业务归属校验（只能改自己收藏行的封面），其余同 {@link #avatarUploadPlan}。
     */
    public QtUploadPlanVo coverUploadPlan(
            Long uid,
            String pid,
            String platform,
            String fileName,
            String contentType,
            long sizeBytes,
            String idempotencyKey) {
        requireOwnPlaylist(uid, pid, platform);
        StorageFolderEntity folder = ensureMediaTree(FOLDER_COVER);
        return mediaUploadPlan(folder, uid, fileName, contentType, sizeBytes, idempotencyKey);
    }

    /**
     * 上传计划分流：CUSTOM_HTTP 渠道不允许客户端直传（STORAGE039——直传凭证会把上游凭据
     * 泄露给客户端），改走两段式代传（正文经 Astral 服务器 HttpUploadGateway 发往上游）。
     * <p>qt 目录为 ADMIN-owned 约定目录（登录即可传，无按用户 ACL 行），目录 UPLOAD 门由
     * folderAclExempt=true 豁免；每日配额由两段式链路的 quota reserve 独立执行（不豁免）。</p>
     */
    private QtUploadPlanVo mediaUploadPlan(
            StorageFolderEntity folder,
            Long uid,
            String fileName,
            String contentType,
            long sizeBytes,
            String idempotencyKey) {
        StorageConfigEntity config = storageTicketTargetService.resolveUploadConfig(folder, uid.toString());
        if (!HttpUploadGateway.PROVIDER_CUSTOM_HTTP.equals(config.getProviderType())) {
            return QtUploadPlanVo.direct(issueTicket(folder, uid, fileName, contentType, sizeBytes));
        }
        UploadOperationDtos.UploadCreateResp operation = uploadOperationService.create(
                new UploadOperationDtos.UploadCreateReq(folder.getId(), fileName, contentType, sizeBytes, null),
                uid.toString(),
                idempotencyKey,
                true);
        return QtUploadPlanVo.proxy(
                operation.uploadId(),
                proxyContentPath(operation.uploadId()),
                operation.maxBytes(),
                operation.intentTtlSeconds());
    }

    /** qt 域两段式代传正文端点（App 端统一入口，正文转发复用插件两段式状态机） */
    private String proxyContentPath(String uploadId) {
        return "/api/v1/app/user/proxy-upload/" + uploadId + "/content";
    }

    /**
     * 两段式代传正文（qt 域入口）：读原始字节 → 复用插件两段式状态机（准入/配额/内容校验/上游发送）。
     * <p>归属不豁免（只能续传本人创建的操作）；qt 目录 ACL 豁免与 plan 一致。
     * 渠道 FAILOVER 时响应 {@code state=CREATED}，客户端重发同一正文即可（失败渠道不会被重试）。</p>
     */
    public QtProxyContentVo proxyUploadContent(
            Long uid, String uploadId, Long contentLength, String contentType, HttpServletRequest request)
            throws IOException {
        UploadOperationService.PreparedContent prepared = uploadOperationService.prepareContent(
                uploadId, contentLength, contentType, uid.toString(), true, false);
        try (prepared) {
            byte[] body = readBounded(request, prepared.maxBytes());
            return QtProxyContentVo.of(uploadOperationService.sendContent(prepared, body));
        }
    }

    /** 读正文并做硬上限保护：与 all 层两段式上传同口径（多读 1 字节判断超限） */
    private byte[] readBounded(HttpServletRequest request, long maxBytes) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
            if (body.length > maxBytes) {
                throw new BusinessException("STORAGE045", "正文超出允许上限");
            }
            return body;
        }
    }

    /**
     * 头像两段式代传收尾：操作 COMMITTED 校验 → 永久 URL → 直写 sys_user.avatar → 异步删旧文件。
     * 幂等：重复 apply 写入同一 URL，无副作用差异。
     */
    public QtUploadCompleteVo avatarProxyApply(Long uid, String uploadId) {
        StorageFolderEntity folder = ensureMediaTree(FOLDER_AVATAR);
        StorageFileEntity file = committedFile(uid, uploadId, folder);
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
        log.info("[QtPlugin] 头像更新（代传）: uid={}, url={}", uid, url);
        return new QtUploadCompleteVo(url);
    }

    /**
     * 歌单封面两段式代传收尾：业务归属校验 → COMMITTED 校验 → 永久 URL →
     * 推进同步 seq 直写 pic_url → 删旧文件（事务边界与 coverComplete 一致）。
     */
    public QtUploadCompleteVo coverProxyApply(Long uid, String pid, String platform, String uploadId) {
        QtLikePlaylist owned = requireOwnPlaylist(uid, pid, platform);
        StorageFolderEntity folder = ensureMediaTree(FOLDER_COVER);
        StorageFileEntity file = committedFile(uid, uploadId, folder);
        String url = permanentUrlOf(file, uid);
        String oldCover = owned.getPicUrl();
        transactionTemplate.executeWithoutResult(status -> {
            likeSyncMapper.lockUser(uid);
            long seq = likeSyncMapper.selectUserMaxSeq(uid) + 1;
            LocalDateTime now = LocalDateTime.now();
            owned.setPicUrl(url);
            owned.setUpdatedSeq(seq);
            owned.setUpdatedAt(now);
            owned.setUpdateTime(now);
            playlistMapper.updateById(owned);
        });
        recycleOldFile(oldCover, uid);
        log.info("[QtPlugin] 歌单封面更新（代传）: uid={}, pid={}, platform={}, url={}", uid, pid, platform, url);
        return new QtUploadCompleteVo(url);
    }

    /**
     * 两段式操作收尾校验：归属（本人操作）+ 目录一致（防跨目录张冠李戴）+
     * {@code COMMITTED} 才允许落到业务字段（fileId 仅在 COMMITTED 后非空）。
     */
    private StorageFileEntity committedFile(Long uid, String uploadId, StorageFolderEntity folder) {
        UploadOperationDtos.UploadOperationView view = uploadOperationService.get(uploadId, uid.toString(), false);
        if (!"COMMITTED".equals(view.state())) {
            throw new QtException("文件未完成上传或未通过校验，当前状态: " + view.state());
        }
        StorageFileEntity file = storageFileMapper.selectById(Long.valueOf(view.fileId()));
        if (file == null
                || !folder.getId().equals(file.getFolderId())
                || !uid.toString().equals(file.getUploaderId())) {
            throw new QtException(403, "上传归属校验失败");
        }
        return file;
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
    public QtUploadTicketVo coverTicket(
            Long uid, String pid, String platform, String fileName, String contentType, long sizeBytes) {
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
    public QtUploadCompleteVo coverComplete(
            Long uid, String pid, String platform, String uploadId, HttpServletRequest request) {
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
    private QtUploadTicketVo issueTicket(
            StorageFolderEntity folder, Long uid, String fileName, String contentType, long sizeBytes) {
        // 统一解析链：授权绑定子集 → 文件夹绑定；无绑定/渠道停用/CUSTOM_HTTP 由该服务明确拒绝
        StorageConfigEntity config = storageTicketTargetService.requireDirectUploadConfig(folder, uid.toString());
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
    private StorageFileEntity registerAndGet(
            String uploadId, String uploaderId, HttpServletRequest request, StorageFolderEntity expectedFolder) {
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
        if (!expectedFolder.getId().equals(file.getFolderId()) || !uploaderId.equals(file.getUploaderId())) {
            throw new QtException(403, "上传归属校验失败");
        }
        if (!StorageFileEntity.STATUS_AVAILABLE.equals(file.getStatus())) {
            throw new QtException("文件未完成上传或未通过校验");
        }
        return file;
    }

    /** 永久 URL 签发 + 长度守卫（业务字段 VARCHAR(256) 容量约束，业务层唯一校验） */
    private String permanentUrlOf(StorageFileEntity file, Long uid) {
        String url = storageFileService
                .issuePermanentUrl(file.getPublicId(), uid.toString())
                .url();
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
        StorageFolderEntity root = findFolder(FOLDER_ROOT, null);
        if (root == null) {
            root = createFolder(config, FOLDER_ROOT, null, null);
        }
        StorageFolderEntity leaf = findFolder(leafName, root.getId());
        if (leaf == null) {
            leaf = createFolder(config, leafName, root, leafPolicy());
        }
        if (!StorageFolderEntity.STATUS_ENABLED.equals(leaf.getStatus())) {
            throw new QtException("存储文件夹已停用，请联系管理员");
        }
        return leaf;
    }

    private StorageFolderEntity findFolder(String folderName, Long parentId) {
        // parent_id 为 NULL 的判断必须走 isNull：eq(column, null) 生成 "parent_id = NULL" 永远不匹配，
        // 会让根文件夹每次调用都被重复创建（幂等性破坏）
        LambdaQueryWrapper<StorageFolderEntity> qw =
                new LambdaQueryWrapper<StorageFolderEntity>().eq(StorageFolderEntity::getFolderName, folderName);
        if (parentId == null) {
            qw.isNull(StorageFolderEntity::getParentId);
        } else {
            qw.eq(StorageFolderEntity::getParentId, parentId);
        }
        return storageFolderMapper.selectOne(qw.last("LIMIT 1"));
    }

    private StorageFolderEntity createFolder(
            StorageConfigEntity config, String folderName, StorageFolderEntity parent, String policyJson) {
        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setParentId(parent == null ? null : parent.getId());
        folder.setFolderName(folderName);
        folder.setFolderPath(parent == null ? "/" + folderName : parent.getFolderPath() + "/" + folderName);
        folder.setStorageStrategy(StorageFolderEntity.STRATEGY_FAILOVER);
        folder.setTargetVersion(1L);
        folder.setOwnerType(StorageFolderEntity.OWNER_ADMIN);
        folder.setOwnerId("qt");
        folder.setVisibility(StorageFolderEntity.VISIBILITY_PUBLIC);
        folder.setStatus(StorageFolderEntity.STATUS_ENABLED);
        folder.setUploadPolicy(policyJson);
        folder.setCreateBy("qt");
        folder.setUpdateBy("qt");
        try {
            storageFolderMapper.insert(folder);
            bindFolderConfig(folder.getId(), config.getId());
            log.info("[QtPlugin] 媒体文件夹已创建: {}", folder.getFolderPath());
        } catch (Exception e) {
            // 并发创建竞态：回查已有行
            StorageFolderEntity existing = findFolder(folderName, parent == null ? null : parent.getId());
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        return folder;
    }

    /** 媒体文件夹绑定默认配置（无绑定 = 禁止上传，凭据签发将直接失败） */
    private void bindFolderConfig(Long folderId, Long configId) {
        StorageFolderConfigEntity binding = new StorageFolderConfigEntity();
        binding.setFolderId(folderId);
        binding.setStorageConfigId(configId);
        binding.setPriority(100);
        storageFolderConfigMapper.insert(binding);
    }

    /**
     * qt 媒体文件夹策略（UPDATE_DESIGN.md §3.1/§5.4）：要求登录、单文件 ≤2MB、
     * png/jpeg/webp、每日每用户 2 次、强制 PUBLIC、登记后完整内容验证。
     */
    private String leafPolicy() {
        Map<String, Object> policy = Map.of(
                "requireLogin",
                true,
                "maxSizeBytes",
                2L * 1024 * 1024,
                "allowedMimes",
                List.of("image/png", "image/jpeg", "image/webp"),
                "allowedExtensions",
                List.of("png", "jpg", "jpeg", "webp"),
                "dailyUploadLimit",
                2,
                "forceVisibility",
                "PUBLIC",
                "verifyContent",
                UploadPolicyService.VERIFY_FULL);
        return uploadPolicyService.normalize(policy);
    }

    /**
     * 旧文件回收（UPDATE_DESIGN.md §11）：业务字段已被新 URL 覆盖后，尽力删除旧 storage 文件。
     * 仅识别 TELEGRAM 永久链形态（可提取 publicId）；对象存储公开 URL 不在此回收
     * （公开 URL 无 publicId 可查）。
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

    /**
     * 登记来源 IP。
     * <p>必须走 {@link ClientIp#resolve}（带可信代理白名单），不能无条件取 XFF 第一段：
     * 该头客户端可任意伪造，直接采信会让 uploader_ip 失去取证价值，也让按 IP 的风控形同虚设。
     * 与 {@code StorageUserController}/{@code AppFeedbackController} 同口径——只有直连方本身是
     * 可信代理时才采信转发头，并从右往左取第一个非可信跳。</p>
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return ClientIp.resolve(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies);
    }
}
