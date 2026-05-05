package com.paper.polish.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("redeem_code")
public class RedeemCode {

    @TableId
    private Long id;

    @TableField("code")
    private String code;

    @TableField("amount")
    private Integer amount;

    @TableField("remain")
    private Integer remain;

    @TableField("used")
    private Integer used;

    @TableField("used_device_id")
    private String usedDeviceId;

    @TableField("used_at")
    private LocalDateTime usedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Integer getAmount() { return amount; }
    public void setAmount(Integer amount) { this.amount = amount; }

    public Integer getRemain() { return remain; }
    public void setRemain(Integer remain) { this.remain = remain; }

    public Integer getUsed() { return used; }
    public void setUsed(Integer used) { this.used = used; }

    public String getUsedDeviceId() { return usedDeviceId; }
    public void setUsedDeviceId(String usedDeviceId) { this.usedDeviceId = usedDeviceId; }

    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}