package com.paper.polish.controller;

import com.paper.polish.common.Result;
import com.paper.polish.service.UserUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UsageController {

    private final UserUsageService userUsageService;

    @GetMapping("/remain")
    public Result<Map<String, Integer>> getRemaining(@RequestParam String deviceId) {
        int remain = userUsageService.getRemainCount(deviceId);
        Map<String, Integer> result = new HashMap<>();
        result.put("remain", remain);
        return Result.ok(result);
    }
}
