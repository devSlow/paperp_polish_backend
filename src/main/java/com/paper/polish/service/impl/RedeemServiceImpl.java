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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RedeemServiceImpl extends ServiceImpl<RedeemCodeMapper, RedeemCode> implements RedeemService {

    private final DailyUsageService dailyUsageService;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedeemResult redeem(String code, String deviceId) {
        RedeemCode redeemCode = this.getOne(new LambdaQueryWrapper<RedeemCode>()
                .eq(RedeemCode::getCode, code));

        if (redeemCode == null) {
            return new RedeemResult(false, "兑换码不存在", 0);
        }

        if (redeemCode.getRemain() <= 0) {
            return new RedeemResult(false, "兑换码已被使用完", 0);
        }

        int give = redeemCode.getAmount();
        redeemCode.setRemain(redeemCode.getRemain() - 1);
        redeemCode.setUsed(redeemCode.getUsed() + 1);
        redeemCode.setUsedDeviceId(deviceId);
        redeemCode.setUsedAt(LocalDateTime.now());
        this.updateById(redeemCode);

        dailyUsageService.recharge(deviceId, give);

        return new RedeemResult(true, "兑换成功", give);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<RedeemCode> generateCodes(int count, int amount, int remain) {
        List<RedeemCode> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RedeemCode rc = new RedeemCode();
            rc.setCode(generateCode());
            rc.setAmount(amount);
            rc.setRemain(remain);
            rc.setUsed(0);
            codes.add(rc);
        }
        this.saveBatch(codes);
        return codes;
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}