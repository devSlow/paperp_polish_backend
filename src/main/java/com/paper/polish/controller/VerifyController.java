package com.paper.polish.controller;

import com.paper.polish.common.JwtUtil;
import com.paper.polish.common.Result;
import com.paper.polish.dto.VerifyCodeDTO;
import com.paper.polish.service.WechatService;
import com.paper.polish.service.UserUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/auth/verify")
@RequiredArgsConstructor
public class VerifyController {

    private final WechatService wechatService;
    private final JwtUtil jwtUtil;
    private final UserUsageService userUsageService;
    private final StringRedisTemplate redisTemplate;

    private static final String CODE_KEY_PREFIX = "verify:code:";
    private static final long EXPIRE_MINUTES = 30;

    @PostMapping("/generate")
    public Result<?> generate() {
        String sessionId = Base64.getEncoder()
                .encodeToString(UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8))
                .substring(0, 16);
        String code = String.valueOf((int) ((Math.random() * 900000) + 100000));

        VerifyCodeDTO dto = new VerifyCodeDTO();
        dto.setSessionId(sessionId);
        dto.setCode(code);
        dto.setViewed(false);

        try {
            String json = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(dto);
            redisTemplate.opsForValue().set(CODE_KEY_PREFIX + sessionId, json, EXPIRE_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Redis 写入失败", e);
            return Result.fail("系统繁忙，请稍后重试");
        }

        log.info("生成验证码 sessionId={}, code={}", sessionId, code);
        return Result.ok(Map.of("sessionId", sessionId));
    }

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

    private VerifyCodeDTO getFromRedis(String sessionId) {
        String key = sessionId.startsWith("v:") ? sessionId.substring(2) : sessionId;
        String json = redisTemplate.opsForValue().get(CODE_KEY_PREFIX + key);
        if (json == null) return null;
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(json, VerifyCodeDTO.class);
        } catch (Exception e) {
            log.error("Redis 反序列化失败", e);
            return null;
        }
    }

    private void deleteFromRedis(String sessionId) {
        redisTemplate.delete(CODE_KEY_PREFIX + sessionId);
    }

    @GetMapping("/code")
    public Result<?> getCode(@RequestParam String sessionId) {
        VerifyCodeDTO dto = getFromRedis(sessionId);
        if (dto == null) {
            log.warn("验证码不存在或已过期 sessionId={}", sessionId);
            return Result.fail(400, "验证码已过期");
        }
        log.info("小程序查看验证码 sessionId={}, code={}", sessionId, dto.getCode());
        return Result.ok(Map.of("code", dto.getCode()));
    }

    @PostMapping("/confirm")
    public Result<?> confirm(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String code = body.get("code");
        if (sessionId == null || code == null) {
            return Result.fail(400, "参数不完整");
        }
        VerifyCodeDTO dto = getFromRedis(sessionId);
        if (dto == null) {
            return Result.fail(400, "验证码已过期");
        }
        if (!dto.getCode().equals(code)) {
            return Result.fail(400, "验证码错误");
        }
        String token = jwtUtil.generate(sessionId);
        deleteFromRedis(sessionId);
        log.info("验证成功 sessionId={}", sessionId);
        return Result.ok(Map.of("token", token));
    }

    @PostMapping("/redeem")
    public Result<?> redeem(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String code = body.get("code");
        String deviceId = body.get("deviceId");
        if (sessionId == null || code == null || deviceId == null) {
            return Result.fail(400, "参数不完整");
        }
        VerifyCodeDTO dto = getFromRedis(sessionId);
        if (dto == null) {
            return Result.fail(400, "验证码已过期");
        }
        if (!dto.getCode().equals(code)) {
            return Result.fail(400, "验证码错误");
        }
        int newRemain = userUsageService.redeem(deviceId, 3);
        deleteFromRedis(sessionId);
        log.info("兑换成功 sessionId={}, deviceId={}, 剩余{}次", sessionId, deviceId, newRemain);
        return Result.ok(Map.of("success", true, "added", 3, "remain", newRemain));
    }
}
