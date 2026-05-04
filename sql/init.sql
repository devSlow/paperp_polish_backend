-- 使用次数表
CREATE TABLE IF NOT EXISTS `user_usage` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `device_id` varchar(128) NOT NULL COMMENT '设备ID',
  `remain_count` int(11) NOT NULL DEFAULT 0 COMMENT '剩余使用次数',
  `total_count` int(11) NOT NULL DEFAULT 0 COMMENT '总使用次数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_id` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户使用次数表';

-- 兑换记录表
CREATE TABLE IF NOT EXISTS `redeem_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `device_id` varchar(128) NOT NULL COMMENT '设备ID',
  `session_id` varchar(128) NOT NULL COMMENT '会话ID',
  `code` varchar(16) NOT NULL COMMENT '验证码',
  `added_count` int(11) NOT NULL COMMENT '兑换增加次数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_id` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='兑换记录表';
