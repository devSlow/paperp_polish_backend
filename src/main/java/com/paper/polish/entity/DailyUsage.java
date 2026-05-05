package com.paper.polish.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;

@TableName("daily_usage")
public class DailyUsage {

    @TableId
    private Long id;

    @TableField("device_id")
    private String deviceId;

    @TableField("usage_date")
    private LocalDate usageDate;

    @TableField("remain")
    private Integer remain;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }

    public Integer getRemain() { return remain; }
    public void setRemain(Integer remain) { this.remain = remain; }
}