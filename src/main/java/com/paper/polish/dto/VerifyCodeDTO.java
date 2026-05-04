package com.paper.polish.dto;

import lombok.Data;

/**
 * 验证码 DTO（内存存储）
 */
@Data
public class VerifyCodeDTO {
    private String sessionId;
    private String code;        // 6位验证码
    private boolean viewed;     // 是否已被查看（一次性）
    private long expireAt;     // 过期时间戳（5分钟）
}
