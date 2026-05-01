package com.paper.polish.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.paper.polish.entity.RedeemCode;

public interface RedeemService extends IService<RedeemCode> {
    RedeemResult redeem(String code, String deviceId);
}

class RedeemResult {
    private boolean success;
    private String message;
    private int amount;

    public RedeemResult(boolean success, String message, int amount) {
        this.success = success;
        this.message = message;
        this.amount = amount;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public int getAmount() { return amount; }
}