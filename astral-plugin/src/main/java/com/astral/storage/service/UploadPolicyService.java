package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件夹级上传策略（UPDATE_DESIGN.md §3.3）
 * <p>策略以 JSON 存于 {@code sys_storage_folder.upload_policy}；NULL = 未配置，
 * 行为与升级前完全一致（requireLogin、全局大小上限、全局 MIME 白名单、不限次数）。</p>
 * <p>解析遵循项目规范：值域手工校验、非法值抛 {@link BusinessException}，不引入枚举框架；
 * {@code forceVisibility} 复用字典 storage_visibility，{@code verifyContent} 复用字典 storage_verify_content。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class UploadPolicyService {

    /** 登记后内容验证级别（storage_verify_content 字典值域） */
    public static final String VERIFY_NONE = "none";
    public static final String VERIFY_MAGIC = "magic";
    public static final String VERIFY_FULL = "full";

    private final StorageFileMapper fileMapper;
    private final ObjectMapper objectMapper;

    /**
     * 解析后的策略视图：字段全部带缺省值，调用方无需判空。
     *
     * @param requireLogin     签发凭证是否必须登录（本期恒为 true；false 为预留）
     * @param minSizeBytes     单文件下限（字节）
     * @param maxSizeBytes     单文件上限（字节）；null = 不按文件夹收紧
     * @param allowedMimes     文件夹级 MIME 白名单；空 = 回落插件全局配置
     * @param allowedExtensions 扩展名白名单（展示/前端预检用，服务端以 MIME 为准）；可空
     * @param dailyUploadLimit 每用户每日上传成功次数上限；null = 不限
     * @param forceVisibility  登记可见性强制值（PRIVATE/PUBLIC）；null = 跟随文件夹默认可见性
     * @param verifyContent    登记后内容验证级别：none/magic/full
     * @param maxPixels        图片像素总数上限；null = 不校验（仅 verifyContent=full 时可执行）
     */
    public record PolicySpec(boolean requireLogin, long minSizeBytes, Long maxSizeBytes,
                             List<String> allowedMimes, List<String> allowedExtensions,
                             Integer dailyUploadLimit, String forceVisibility,
                             String verifyContent, Long maxPixels) {
    }

    /** 解析文件夹策略；未配置返回全缺省值 */
    public PolicySpec resolve(StorageFolderEntity folder) {
        if (folder == null || folder.getUploadPolicy() == null || folder.getUploadPolicy().isBlank()) {
            return new PolicySpec(true, 0, null, List.of(), List.of(), null, null, VERIFY_NONE, null);
        }
        return parse(folder.getUploadPolicy());
    }

    /** 解析策略 JSON 串（库内已归一化的值）；损坏按策略非法报错 */
    public PolicySpec parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new PolicySpec(
                    node.path("requireLogin").asBoolean(true),
                    Math.max(0, node.path("minSizeBytes").asLong(0)),
                    node.hasNonNull("maxSizeBytes") && node.get("maxSizeBytes").isNumber()
                            && node.get("maxSizeBytes").asLong() > 0 ? node.get("maxSizeBytes").asLong() : null,
                    readStringList(node.get("allowedMimes")),
                    readStringList(node.get("allowedExtensions")),
                    node.hasNonNull("dailyUploadLimit") && node.get("dailyUploadLimit").isInt()
                            && node.get("dailyUploadLimit").asInt() > 0 ? node.get("dailyUploadLimit").asInt() : null,
                    node.hasNonNull("forceVisibility") ? node.get("forceVisibility").asText() : null,
                    node.hasNonNull("verifyContent") ? node.get("verifyContent").asText(VERIFY_NONE) : VERIFY_NONE,
                    node.hasNonNull("maxPixels") && node.get("maxPixels").isNumber()
                            && node.get("maxPixels").asLong() > 0 ? node.get("maxPixels").asLong() : null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("STORAGE029", "策略解析失败: " + e.getMessage());
        }
    }

    /**
     * 归一化并校验前端提交的策略对象，落库为紧凑 JSON。
     *
     * @param rawPolicy 前端策略对象；null 或空对象 → 返回 null（清除策略）
     */
    public String normalize(Map<String, Object> rawPolicy) {
        if (rawPolicy == null || rawPolicy.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(rawPolicy));
            ObjectNode out = objectMapper.createObjectNode();

            out.put("requireLogin", node.path("requireLogin").asBoolean(true));

            long minSize = Math.max(0, node.path("minSizeBytes").asLong(0));
            if (minSize > 0) {
                out.put("minSizeBytes", minSize);
            }
            if (node.hasNonNull("maxSizeBytes")) {
                long maxSize = node.get("maxSizeBytes").asLong(-1);
                if (maxSize <= 0) {
                    throw new BusinessException("STORAGE029", "maxSizeBytes 必须为正数");
                }
                if (maxSize < minSize) {
                    throw new BusinessException("STORAGE029", "maxSizeBytes 不能小于 minSizeBytes");
                }
                out.put("maxSizeBytes", maxSize);
            }

            List<String> mimes = normalizeList(node.get("allowedMimes"), "allowedMimes");
            if (!mimes.isEmpty()) {
                ArrayNode arr = out.putArray("allowedMimes");
                mimes.forEach(arr::add);
            }
            List<String> exts = normalizeList(node.get("allowedExtensions"), "allowedExtensions");
            if (!exts.isEmpty()) {
                ArrayNode arr = out.putArray("allowedExtensions");
                exts.forEach(arr::add);
            }

            if (node.hasNonNull("dailyUploadLimit")) {
                int limit = node.get("dailyUploadLimit").asInt(-1);
                if (limit < 0) {
                    throw new BusinessException("STORAGE029", "dailyUploadLimit 不能为负数");
                }
                if (limit > 0) {
                    out.put("dailyUploadLimit", limit);
                }
            }

            if (node.hasNonNull("forceVisibility") && !node.get("forceVisibility").isNull()) {
                String visibility = node.get("forceVisibility").asText("").trim().toUpperCase(Locale.ROOT);
                if (!StorageFolderEntity.VISIBILITY_PRIVATE.equals(visibility)
                        && !StorageFolderEntity.VISIBILITY_PUBLIC.equals(visibility)) {
                    throw new BusinessException("STORAGE029", "forceVisibility 仅支持 PRIVATE/PUBLIC");
                }
                out.put("forceVisibility", visibility);
            }

            String verify = node.hasNonNull("verifyContent") && !node.get("verifyContent").isNull()
                    ? node.get("verifyContent").asText(VERIFY_NONE).trim().toLowerCase(Locale.ROOT)
                    : VERIFY_NONE;
            if (!VERIFY_NONE.equals(verify) && !VERIFY_MAGIC.equals(verify) && !VERIFY_FULL.equals(verify)) {
                throw new BusinessException("STORAGE029", "verifyContent 仅支持 none/magic/full");
            }
            if (!VERIFY_NONE.equals(verify)) {
                out.put("verifyContent", verify);
            }

            if (node.hasNonNull("maxPixels")) {
                long maxPixels = node.get("maxPixels").asLong(-1);
                if (maxPixels <= 0) {
                    throw new BusinessException("STORAGE029", "maxPixels 必须为正数");
                }
                if (!VERIFY_FULL.equals(verify)) {
                    throw new BusinessException("STORAGE029", "maxPixels 需要 verifyContent=full 才能生效");
                }
                out.put("maxPixels", maxPixels);
            }

            return objectMapper.writeValueAsString(out);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("STORAGE029", "策略格式非法: " + e.getMessage());
        }
    }

    /**
     * 每日上传计数（UPDATE_DESIGN.md §5.4）：该文件夹 + 该上传者当日已成功上传次数。
     * <p>计数口径：全部行中排除 FAILED（内容验证拒绝，未成功）；含 DELETED/DELETING ——
     * storage 删除为软删、行永不物理删除，替换回收旧文件后当日计数依然准确。
     * 自然日按服务器时区。</p>
     */
    public long countTodayUploads(Long folderId, String uploaderId) {
        if (folderId == null || uploaderId == null || uploaderId.isBlank()) {
            return 0;
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Long count = fileMapper.selectCount(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getFolderId, folderId)
                .eq(StorageFileEntity::getUploaderId, uploaderId)
                .ge(StorageFileEntity::getCreateTime, dayStart)
                .ne(StorageFileEntity::getStatus, StorageFileEntity.STATUS_FAILED));
        return count == null ? 0 : count;
    }

    /** 配额检查：超出抛 STORAGE032（每日上传次数用尽）。管理端豁免由调用方判断后跳过本方法。 */
    public void checkDailyQuota(StorageFolderEntity folder, PolicySpec policy, String uploaderId) {
        if (policy.dailyUploadLimit() == null) {
            return;
        }
        long used = countTodayUploads(folder.getId(), uploaderId);
        if (used >= policy.dailyUploadLimit()) {
            throw new BusinessException("STORAGE032");
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    private List<String> normalizeList(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BusinessException("STORAGE029", field + " 必须为字符串数组");
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> {
            String v = n.asText("").trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) {
                out.add(v);
            }
        });
        return List.copyOf(out);
    }
}
