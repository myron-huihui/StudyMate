package com.studymate.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studymate.common.entity.ChatMemoryRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMemoryMapper extends BaseMapper<ChatMemoryRecord> {
}
