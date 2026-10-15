package com.studymate.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studymate.common.entity.DailyTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DailyTaskMapper extends BaseMapper<DailyTask> {
}
