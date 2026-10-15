package com.studymate.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studymate.common.entity.RefreshToken;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
}
