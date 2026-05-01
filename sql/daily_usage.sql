-- 设备每日使用次数限制表
CREATE TABLE IF NOT EXISTS `daily_usage` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `device_id` VARCHAR(64) NOT NULL COMMENT '设备指纹',
  `usage_date` DATE NOT NULL COMMENT '使用日期',
  `count` INT NOT NULL DEFAULT 0 COMMENT '当天已使用次数',
  UNIQUE KEY `uk_device_date` (`device_id`, `usage_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备每日使用次数限制表';