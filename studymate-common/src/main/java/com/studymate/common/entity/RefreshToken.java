package com.studymate.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌：只存 SHA-256 哈希；同一 family 的令牌可被整体吊销（复用检测）
 */
@Data
@TableName("t_refresh_token")
public class RefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String tokenHash;
    private String familyId;
    private LocalDateTime expiresAt;
    private Boolean revoked;
    private String replacedBy;
    private LocalDateTime createdAt;
}
