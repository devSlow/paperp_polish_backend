package com.paper.polish.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user_usage")
public class UserUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceId;
    private Integer remainCount;
    private Integer totalCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
