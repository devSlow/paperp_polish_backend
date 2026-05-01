package com.paper.polish.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paper.polish.entity.DailyUsage;
import com.paper.polish.mapper.DailyUsageMapper;
import com.paper.polish.service.DailyUsageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class DailyUsageServiceImpl extends ServiceImpl<DailyUsageMapper, DailyUsage> implements DailyUsageService {

    @Override
    public int getRemaining(String deviceId) {
        LocalDate today = LocalDate.now();
        DailyUsage record = this.getOne(new LambdaQueryWrapper<DailyUsage>()
                .eq(DailyUsage::getDeviceId, deviceId)
                .eq(DailyUsage::getUsageDate, today));
        if (record == null) {
            return 10;
        }
        return Math.max(0, 10 - record.getCount());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean consume(String deviceId) {
        LocalDate today = LocalDate.now();
        DailyUsage record = this.getOne(new LambdaQueryWrapper<DailyUsage>()
                .eq(DailyUsage::getDeviceId, deviceId)
                .eq(DailyUsage::getUsageDate, today));

        if (record == null) {
            DailyUsage newRecord = new DailyUsage();
            newRecord.setDeviceId(deviceId);
            newRecord.setUsageDate(today);
            newRecord.setCount(1);
            this.save(newRecord);
            return true;
        }

        if (record.getCount() >= 10) {
            return false;
        }

        record.setCount(record.getCount() + 1);
        this.updateById(record);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recharge(String deviceId, int amount) {
        LocalDate today = LocalDate.now();
        DailyUsage record = this.getOne(new LambdaQueryWrapper<DailyUsage>()
                .eq(DailyUsage::getDeviceId, deviceId)
                .eq(DailyUsage::getUsageDate, today));

        if (record == null) {
            record = new DailyUsage();
            record.setDeviceId(deviceId);
            record.setUsageDate(today);
            record.setCount(Math.max(0, 10 - amount));
            this.save(record);
        } else {
            record.setCount(Math.max(0, 10 - (record.getCount() + amount)));
            this.updateById(record);
        }
    }
}