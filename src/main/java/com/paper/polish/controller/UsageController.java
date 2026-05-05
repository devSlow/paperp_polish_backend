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
public class UsageController {

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
    public Result<?> redeem(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        String deviceId = body.get("deviceId");
        if (code == null || deviceId == null) {
            return Result.fail(400, "参数不完整");
        }
        RedeemResult res = redeemService.redeem(code, deviceId);
        if (!res.isSuccess()) {
            return Result.fail(400, res.getMessage());
        }
        int remain = dailyUsageService.getRemaining(deviceId);
        return Result.ok(Map.of("success", true, "message", res.getMessage(), "added", res.getAmount(), "remain", remain));
    }
}
