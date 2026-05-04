package com.paper.polish.controller;

import com.paper.polish.common.JwtUtil;
import com.paper.polish.common.Result;
import com.paper.polish.dto.VerifyCodeDTO;
import com.paper.polish.service.WechatService;
import com.paper.polish.service.UserUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫码验证控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/verify")
@RequiredArgsConstructor
public class VerifyController {

    private final WechatService wechatService;
    private final JwtUtil jwtUtil;
    private final UserUsageService userUsageService;

    // 内存存储：sessionId -> VerifyCodeDTO
    private final ConcurrentHashMap<String, VerifyCodeDTO> codeCache = new ConcurrentHashMap<>();

    /**
     * 生成验证码 + sessionId
     */
    @PostMapping("/generate")
    public Result<?> generate() {
        String sessionId = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        String code = String.valueOf((int) ((Math.random() * 900000) + 100000)); // 6位
        VerifyCodeDTO dto = new VerifyCodeDTO();
        dto.setSessionId(sessionId);
        dto.setCode(code);
        dto.setViewed(false);
        dto.setExpireAt(System.currentTimeMillis() + 30 * 60 * 1000L); // 30分钟，方便测试
        codeCache.put(sessionId, dto);
        log.info("生成验证码 sessionId={}, code={}", sessionId, code);
        return Result.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 获取二维码图片
     */
    @GetMapping(value = "/qrcode")
    public void getQrCode(@RequestParam String sessionId, HttpServletResponse response) throws IOException {
        byte[] qrCode = wechatService.generateQrCode(sessionId);
        if (qrCode == null) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        response.setContentType("image/png");
        response.getOutputStream().write(qrCode);
    }

    /**
     * 小程序获取验证码（一次性查看）
     */
    @GetMapping("/code")
    public Result<?> getCode(@RequestParam String sessionId) {
        // 兼容扫码 scene 带 v: 前缀的情况
        String key = sessionId.startsWith("v:") ? sessionId.substring(2) : sessionId;
        VerifyCodeDTO dto = codeCache.get(key);
        if (dto == null || System.currentTimeMillis() > dto.getExpireAt()) {
            log.warn("验证码不存在或已过期 sessionId={}, key={}", sessionId, key);
            return Result.fail(400, "验证码已过期");
        }
        log.info("小程序查看验证码 sessionId={}, code={}", key, dto.getCode());
        return Result.ok(Map.of("code", dto.getCode()));
    }

    /**
     * 网页确认验证码，返回JWT
     */
    @PostMapping("/confirm")
    public Result<?> confirm(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String code = body.get("code");
        if (sessionId == null || code == null) {
            return Result.fail(400, "参数不完整");
        }
        VerifyCodeDTO dto = codeCache.get(sessionId);
        if (dto == null || System.currentTimeMillis() > dto.getExpireAt()) {
            return Result.fail(400, "验证码已过期");
        }
        if (!dto.getCode().equals(code)) {
            return Result.fail(400, "验证码错误");
        }
        // 验证通过，生成JWT
        String token = jwtUtil.generate(sessionId);
        codeCache.remove(sessionId); // 验证通过，删除记录
        log.info("验证成功 sessionId={}", sessionId);
        return Result.ok(Map.of("token", token));
    }

    /**
     * 定时清理过期码（每5分钟）
     */
    @Scheduled(fixedRate = 300000)
    public void cleanExpired() {
        long now = System.currentTimeMillis();
        int before = codeCache.size();
        codeCache.entrySet().removeIf(e -> now > e.getValue().getExpireAt());
        int removed = before - codeCache.size();
        if (removed > 0) {
            log.info("清理过期验证码 {} 条", removed);
        }
    }

    /**
     * 兑换验证码，增加使用次数
     */
    @PostMapping("/redeem")
    public Result<?> redeem(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String code = body.get("code");
        String deviceId = body.get("deviceId");
        if (sessionId == null || code == null || deviceId == null) {
            return Result.fail(400, "参数不完整");
        }
        VerifyCodeDTO dto = codeCache.get(sessionId);
        if (dto == null || System.currentTimeMillis() > dto.getExpireAt()) {
            return Result.fail(400, "验证码已过期");
        }
        if (!dto.getCode().equals(code)) {
            return Result.fail(400, "验证码错误");
        }
        // 验证通过，增加3次使用次数
        int newRemain = userUsageService.redeem(deviceId, 3);
        codeCache.remove(sessionId);
        log.info("兑换成功 sessionId={}, deviceId={}, 剩余{}次", sessionId, deviceId, newRemain);
        return Result.ok(Map.of("success", true, "added", 3, "remain", newRemain));
    }
}
