package com.paper.polish.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("paper")
public class Paper {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    private String originalFilePath;

    private String latestFilePath;

    private String pdfPath;

    private String status;

    private Integer paragraphCount;

    private Integer rewrittenCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
