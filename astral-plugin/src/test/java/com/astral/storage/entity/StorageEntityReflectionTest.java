package com.astral.storage.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * storage 实体与 MyBatis 反射层的契约测试。
 *
 * <p><b>为什么需要</b>：MyBatis 的 {@code Reflector} 会把 {@code getXxx} 与 {@code isXxx} 都收作属性
 * {@code xxx} 的读取器。若字段是包装类型（Lombok 生成 {@code Boolean getXxx()}）而又手写
 * {@code boolean isXxx()}，两个读取器返回类型不一致，{@code Reflector} 便把它们包成
 * {@code AmbiguousMethodInvoker}——构造阶段不报错，SELECT 走 resultMap 也不报错，
 * 只有 {@code updateById} 在 {@code DefaultParameterHandler} 里按属性名取值时才抛
 * {@code ReflectionException: Illegal overloaded getter method with ambiguous type}，
 * 表现为「表单保存转圈后提示系统繁忙」而列表页完全正常。
 *
 * <p>本测试直接用 {@link SystemMetaObject} 复现该取值路径，因此不依赖数据库即可锁住这个回归。
 */
class StorageEntityReflectionTest {

    /** storage 全部手写实体；新增实体时一并加入，保证同类问题不会再漏。 */
    private static final List<Class<?>> STORAGE_ENTITIES = List.of(
            StorageAuditEntity.class,
            StorageConfigEntity.class,
            StorageFileEntity.class,
            StorageFolderConfigEntity.class,
            StorageFolderEntity.class,
            StorageFolderPermissionEntity.class,
            StoragePermissionConfigEntity.class,
            StorageTaskEntity.class,
            StorageUploadChargeEntity.class,
            StorageUploadOperationEntity.class,
            StorageUploadTicketEntity.class);

    @Test
    @DisplayName("uploadAclRequired 属性能被 MyBatis 按名读写（不允许重载 getter）")
    void uploadAclPropertyIsReadableByMybatis() {
        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setUploadAclRequired(false);

        MetaObject metaObject = SystemMetaObject.forObject(folder);
        // updateById 绑定 SET upload_acl_required=? 时走的正是这条路径
        assertEquals(Boolean.FALSE, metaObject.getValue("uploadAclRequired"));

        metaObject.setValue("uploadAclRequired", Boolean.TRUE);
        assertEquals(Boolean.TRUE, folder.getUploadAclRequired());
    }

    @Test
    @DisplayName("目录授权语义：null 视为要求授权，显式 false 才放行")
    void requiresUploadAclTreatsNullAsRequired() {
        StorageFolderEntity legacy = new StorageFolderEntity();
        assertTrue(legacy.requiresUploadAcl(), "旧读取对象缺列时应按「要求授权」处理");

        StorageFolderEntity closed = new StorageFolderEntity();
        closed.setUploadAclRequired(false);
        assertFalse(closed.requiresUploadAcl());

        StorageFolderEntity opened = new StorageFolderEntity();
        opened.setUploadAclRequired(true);
        assertTrue(opened.requiresUploadAcl());
    }

    @Test
    @DisplayName("任一 storage 实体的任一可读属性都能被 MyBatis 取名取值")
    void everyStorageEntityPropertyIsUnambiguous() {
        for (Class<?> type : STORAGE_ENTITIES) {
            Object entity;
            try {
                entity = type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(type.getSimpleName() + " 需要可用的无参构造以参与该契约测试", e);
            }
            MetaObject metaObject = SystemMetaObject.forObject(entity);
            String[] readable = metaObject.getGetterNames();
            assertNotNull(readable, type.getSimpleName() + " 应至少暴露一个可读属性");
            for (String property : readable) {
                try {
                    metaObject.getValue(property);
                } catch (org.apache.ibatis.reflection.ReflectionException e) {
                    throw new AssertionError(type.getSimpleName() + "." + property + " 的读取器存在冲突（重载 getter / 类型不一致）", e);
                }
            }
        }
    }
}
