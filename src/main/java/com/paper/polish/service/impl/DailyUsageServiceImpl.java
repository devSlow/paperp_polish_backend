package com.paper.polish.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paper.polish.entity.DailyUsage;
import com.paper.polish.mapper.DailyUsageMapper;
import com.paper.polish.service.DailyUsageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DailyUsageServiceImpl extends ServiceImpl<DailyUsageMapper, DailyUsage> implements DailyUsageService {

    private static final int DAILY_LIMIT = 5;

    private DailyUsage getOrCreate(String deviceId) {
        LocalDate today = LocalDate.now();
        DailyUsage record = this.getOne(new LambdaQueryWrapper<DailyUsage>()
                .eq(DailyUsage::getDeviceId, deviceId)
                .eq(DailyUsage::getUsageDate, today));
        if (record == null) {
            record = new DailyUsage();
            record.setDeviceId(deviceId);
            record.setUsageDate(today);
            record.setRemain(DAILY_LIMIT);
            this.save(record);
            log.info("[DailyUsage] 新建设备记录 deviceId={}, date={}, remain={}", deviceId, today, DAILY_LIMIT);
        } else {
            log.info("[DailyUsage] 命中记录 deviceId={}, date={}, remain={}", deviceId, today, record.getRemain());
        }
        return record;
    }

    @Override
    public int getRemaining(String deviceId) {
        int remain = getOrCreate(deviceId).getRemain();
        log.info("[DailyUsage] getRemaining deviceId={}, remain={}", deviceId, remain);
        return remain;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean consume(String deviceId) {
        DailyUsage record = getOrCreate(deviceId);
        if (record.getRemain() <= 0) {
            log.warn("[DailyUsage] 次数不足 deviceId={}, remain={}", deviceId, record.getRemain());
            return false;
        }
        record.setRemain(record.getRemain() - 1);
        this.updateById(record);
        log.info("[DailyUsage] consume deviceId={}, remain={}", deviceId, record.getRemain());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(String deviceId) {
        DailyUsage record = getOrCreate(deviceId);
        record.setRemain(record.getRemain() + 1);
        this.updateById(record);
        log.info("[DailyUsage] rollback deviceId={}, remain={}", deviceId, record.getRemain());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recharge(String deviceId, int amount) {
        DailyUsage record = getOrCreate(deviceId);
        int before = record.getRemain();
        record.setRemain(record.getRemain() + amount);
        this.updateById(record);
        log.info("[DailyUsage] recharge deviceId={}, +{}, remain: {} -> {}", deviceId, amount, before, record.getRemain());
    }
}