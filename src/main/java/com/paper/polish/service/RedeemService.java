package com.paper.polish.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.paper.polish.entity.RedeemCode;

public interface RedeemService extends IService<RedeemCode> {
    RedeemResult redeem(String code, String deviceId);
}

