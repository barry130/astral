package com.astral.feedback.mapper;

import com.astral.feedback.entity.Feedback;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 反馈主表 Mapper
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
