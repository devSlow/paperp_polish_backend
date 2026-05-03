package com.paper.polish.controller;

import com.paper.polish.common.Result;
import com.paper.polish.service.DailyUsageService;
import com.paper.polish.service.RedeemResult;
import com.paper.polish.service.RedeemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DailyUsageController {

    private final DailyUsageService dailyUsageService;
    private final RedeemService redeemService;

    @GetMapping("/remain")
    public Result<Map<String, Integer>> getRemaining(@RequestParam String deviceId) {
        int remain = dailyUsageService.getRemaining(deviceId);
        Map<String, Integer> result = new HashMap<>();
        result.put("remain", remain);
        return Result.ok(result);
    }

    @PostMapping("/redeem")
    public Result<Map<String, Object>> redeem(@RequestBody RedeemRequest request) {
        String deviceId = request.getDeviceId();
        String code = request.getCode();

        RedeemResult result = redeemService.redeem(code, deviceId);

        Map<String, Object> map = new HashMap<>();
        map.put("success", result.isSuccess());
        map.put("message", result.getMessage());
        if (result.isSuccess()) {
            map.put("remain", dailyUsageService.getRemaining(deviceId));
        }
        return Result.ok(map);
    }

    public static class UsageRequest {
        private String deviceId;
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    }

    public static class RedeemRequest {
        private String deviceId;
        private String code;
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }
}