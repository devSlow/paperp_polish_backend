package com.paper.polish.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.paper.polish.entity.DailyUsage;

public interface DailyUsageService extends IService<DailyUsage> {
    int getRemaining(String deviceId);
    boolean consume(String deviceId);
    void recharge(String deviceId, int amount);
}