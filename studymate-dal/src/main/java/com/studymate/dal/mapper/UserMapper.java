package com.studymate.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studymate.common.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
