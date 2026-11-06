package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 图片内容校验（§11.3）：只允许 JPEG/PNG，魔数与声明 MIME 必须一致，且尺寸/像素数在安全上限内。
 *
 * <p>只用 {@link ImageReader} 读**元数据**（宽高），不做整图解码：既避免解压炸弹，也把 CPU 与内存
 * 控制在常量级。校验失败一律 STORAGE045（正文与声明不一致），不回传内部细节。</p>
 */
@Slf4j
@Component
public class UploadImageValidator {

    /** 单边像素上限 */
    public static final int MAX_SIDE = 16384;
    /** 总像素上限（20M） */
    public static final long MAX_PIXELS = 20_000_000L;

    private static final int PNG_SIGNATURE_LENGTH = 8;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** 校验 JPEG/PNG 内容；任何不符合都抛 STORAGE045 */
    public void validate(byte[] content, String declaredMime) {
        if (content == null || content.length == 0) {
            throw invalid("正文为空");
        }
        String format = detectFormat(content);
        if (format == null) {
            throw invalid("不是 JPEG/PNG 内容");
        }
        if (declaredMime != null && !declaredMime.isBlank()) {
            String normalized = declaredMime.toLowerCase(Locale.ROOT);
            String expected = "JPEG".equals(format) ? "image/jpeg" : "image/png";
            if (!expected.equals(normalized)) {
                throw invalid("内容格式与声明 MIME 不一致");
            }
        }
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw invalid("无法识别的图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw invalid("图片尺寸非法");
                }
                if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                    throw invalid("图片尺寸超出上限");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            log.debug("图片元数据读取失败: {}", ex.getMessage());
            throw invalid("图片元数据不可读");
        }
    }

    /** 魔数判定：返回 JPEG / PNG / null */
    public String detectFormat(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8) {
            return "JPEG";
        }
        if (content.length >= PNG_SIGNATURE_LENGTH) {
            boolean png = true;
            for (int i = 0; i < PNG_SIGNATURE_LENGTH; i++) {
                if (content[i] != PNG_SIGNATURE[i]) {
                    png = false;
                    break;
                }
            }
            if (png) {
                return "PNG";
            }
        }
        return null;
    }

    private BusinessException invalid(String reason) {
        return new BusinessException("STORAGE045", reason);
    }
}
