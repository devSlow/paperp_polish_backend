package com.paper.polish.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.paper.polish.entity.RedeemCode;

import java.util.List;

public interface RedeemService extends IService<RedeemCode> {
    RedeemResult redeem(String code, String deviceId);

    List<RedeemCode> generateCodes(int count, int amount);
}

