package com.astral.cms.mapper;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/** Tree validation and mutations share a transaction-scoped lock across application instances. */
public interface CmsTreeMapper {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended('cms:page-tree', 0))")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    String lockPages();

    @Select("SELECT pg_advisory_xact_lock(hashtextextended('cms:category-tree', 0))")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    String lockCategories();
}
