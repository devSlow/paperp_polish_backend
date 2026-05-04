package com.paper.polish.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paper.polish.entity.UserUsage;
import com.paper.polish.mapper.UserUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserUsageService {
    private final UserUsageMapper userUsageMapper;

    /**
     * 获取剩余次数，没有记录就初始化
     */
    public int getRemainCount(String deviceId) {
        UserUsage usage = getOrCreate(deviceId);
        return usage.getRemainCount();
    }

    /**
     * 兑换增加次数，返回增加后的剩余次数
     */
    @Transactional
    public int redeem(String deviceId, int addCount) {
        UserUsage usage = getOrCreate(deviceId);
        int newRemain = usage.getRemainCount() + addCount;
        usage.setRemainCount(newRemain);
        usage.setTotalCount(usage.getTotalCount() + addCount);
        userUsageMapper.updateById(usage);
        log.info("兑换成功 deviceId={}, 增加{}次, 剩余{}次", deviceId, addCount, newRemain);
        return newRemain;
    }

    private UserUsage getOrCreate(String deviceId) {
        LambdaQueryWrapper<UserUsage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserUsage::getDeviceId, deviceId);
        UserUsage usage = userUsageMapper.selectOne(wrapper);
        if (usage == null) {
            usage = new UserUsage();
            usage.setDeviceId(deviceId);
            usage.setRemainCount(10); // 默认10次
            usage.setTotalCount(10);
            userUsageMapper.insert(usage);
            log.info("初始化用户次数 deviceId={}, 剩余{}次", deviceId, 10);
        }
        return usage;
    }
}
