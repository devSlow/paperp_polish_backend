package com.paper.polish.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paper.polish.entity.RedeemCode;
import com.paper.polish.mapper.RedeemCodeMapper;
import com.paper.polish.service.DailyUsageService;
import com.paper.polish.service.RedeemResult;
import com.paper.polish.service.RedeemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RedeemServiceImpl extends ServiceImpl<RedeemCodeMapper, RedeemCode> implements RedeemService {

    private final DailyUsageService dailyUsageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedeemResult redeem(String code, String deviceId) {
        RedeemCode redeemCode = this.getOne(new LambdaQueryWrapper<RedeemCode>()
                .eq(RedeemCode::getCode, code));

        if (redeemCode == null) {
            return new RedeemResult(false, "兑换码不存在", 0);
        }

        if (redeemCode.getUsed() == 1) {
            return new RedeemResult(false, "兑换码已被使用", 0);
        }

        redeemCode.setUsed(1);
        redeemCode.setUsedDeviceId(deviceId);
        redeemCode.setUsedAt(LocalDateTime.now());
        this.updateById(redeemCode);

        dailyUsageService.recharge(deviceId, redeemCode.getAmount());

        return new RedeemResult(true, "兑换成功", redeemCode.getAmount());
    }
}