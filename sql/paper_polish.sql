-- ============================================
-- PaperPolish - 论文优化润色工具 数据库初始化脚本
-- 适配 MySQL 5.7
-- ============================================

CREATE DATABASE IF NOT EXISTS paper_polish DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE paper_polish;

-- ----------------------------
-- 论文表
-- ----------------------------
DROP TABLE IF EXISTS `paragraph`;
DROP TABLE IF EXISTS `paper`;

CREATE TABLE `paper` (
    `id`                VARCHAR(36)     NOT NULL COMMENT '论文唯一ID',
    `user_id`           VARCHAR(36)     NULL COMMENT '用户ID（暂不使用）',
    `original_file_path` VARCHAR(500)   NOT NULL COMMENT '原始上传的Word文件路径（永远不动）',
    `latest_file_path`  VARCHAR(500)    NULL COMMENT '最新导出的Word路径',
    `status`            VARCHAR(20)     NOT NULL DEFAULT 'uploaded' COMMENT '状态：uploaded/parsed/finished',
    `paragraph_count`   INT             DEFAULT 0 COMMENT '段落总数',
    `rewritten_count`   INT             DEFAULT 0 COMMENT '已优化段数',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文表';

-- ----------------------------
-- 段落表
-- ----------------------------
CREATE TABLE `paragraph` (
    `id`              VARCHAR(36)    NOT NULL COMMENT '主键',
    `paper_id`        VARCHAR(36)    NOT NULL COMMENT '所属论文ID',
    `paragraph_index` INT            NOT NULL COMMENT '段落索引',
    `type`            VARCHAR(20)    NOT NULL DEFAULT 'paragraph' COMMENT '类型：heading/paragraph',
    `content_type`    VARCHAR(20)    NOT NULL DEFAULT 'text' COMMENT '内容类型：text/image/table',
    `location_type`   VARCHAR(20)    NOT NULL DEFAULT 'body' COMMENT '位置：body/table',
    `table_index`     INT            NULL COMMENT '表格索引',
    `row_index`       INT            NULL COMMENT '行索引',
    `cell_index`      INT            NULL COMMENT '列索引',
    `original_text`   TEXT           NULL COMMENT '原始文本',
    `current_text`    TEXT           NULL COMMENT '当前文本',
    `rewritten_text`  TEXT           NULL COMMENT 'AI润色结果',
    `image_url`       VARCHAR(500)   NULL COMMENT '图片URL（多图用|||分隔）',
    `table_data`      TEXT           NULL COMMENT '表格JSON数据',
    `can_rewrite`     TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '是否允许润色',
    `status`          VARCHAR(20)    NOT NULL DEFAULT 'original' COMMENT '状态：original/rewritten/replaced',
    `created_at`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_paper_id` (`paper_id`),
    INDEX `idx_paper_paragraph` (`paper_id`, `paragraph_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='段落表';
