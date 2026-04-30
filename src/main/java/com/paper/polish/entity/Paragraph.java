package com.paper.polish.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("paragraph")
public class Paragraph {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String paperId;

    private Integer paragraphIndex;

    private String type;

    private String contentType;

    private String locationType;

    private Integer tableIndex;

    private Integer rowIndex;

    private Integer cellIndex;

    private String originalText;

    private String currentText;

    private String rewrittenText;

    private String imageUrl;

    private String tableData;

    private String styleData;

    private Boolean canRewrite;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
