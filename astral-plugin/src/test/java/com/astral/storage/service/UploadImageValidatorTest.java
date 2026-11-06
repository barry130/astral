package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.astral.common.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UploadImageValidatorTest {

    private UploadImageValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UploadImageValidator();
    }

    @Test
    void acceptsPngAndJpegMatchingDeclaredMime() {
        assertDoesNotThrow(() -> validator.validate(image("png"), "image/png"));
        assertDoesNotThrow(() -> validator.validate(image("jpeg"), "image/jpeg"));
        // 未声明 MIME 时只看魔数
        assertDoesNotThrow(() -> validator.validate(image("png"), null));
    }

    @Test
    void declaredMimeMustMatchContentMagic() {
        BusinessException ex =
                assertThrows(BusinessException.class, () -> validator.validate(image("png"), "image/jpeg"));
        assertEquals("STORAGE045", ex.getErrorCode());
    }

    @Test
    void nonImageContentIsRejected() {
        BusinessException ex =
                assertThrows(BusinessException.class, () -> validator.validate("not an image".getBytes(), "image/png"));
        assertEquals("STORAGE045", ex.getErrorCode());
    }

    @Test
    void emptyContentIsRejected() {
        BusinessException ex =
                assertThrows(BusinessException.class, () -> validator.validate(new byte[0], "image/png"));
        assertEquals("STORAGE045", ex.getErrorCode());
    }

    private byte[] image(String format) {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0xFF0000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
