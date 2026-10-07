package com.astral.storage.mapper;

import com.astral.storage.entity.StorageFileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * sys_storage_file Mapper
 */
@Mapper
public interface StorageFileMapper extends BaseMapper<StorageFileEntity> {

    /**
     * 事务级咨询锁：同一 uploadId 的登记（回调 / 浏览器回执）串行化。
     * <p>登记路径是「先查后插」的幂等实现，并发重复回执时两个事务都能读到「不存在」而各插一行
     * （同一凭证重复登记，文件列表重复、配额重复计数）。表上 {@code uq_storage_file_upload}
     * 唯一索引是最后一道防线，本锁让幂等判断本身不再有竞态——事务提交/回滚自动释放。
     * 注意：PG 对 void 函数的 SELECT 会返回函数名字符串，故声明为 String 接收（同
     * {@code QtLikeSyncMapper#lockUser}）。键用 {@code hashtextextended} 取 64 位哈希，
     * 冲突只导致无关上传串行，不影响正确性。</p>
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    String lockUploadKey(@Param("key") String key);
}
