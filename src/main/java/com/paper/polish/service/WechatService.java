package com.paper.polish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paper.polish.config.WechatConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 微信小程序服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatService {

    private final WechatConfig wechatConfig;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String accessToken;
    private long tokenExpireAt;

    /**
     * 获取微信 access_token（缓存2小时，提前5分钟刷新）
     */
    public synchronized String getAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return accessToken;
        }
        String url = "https://api.weixin.qq.com/cgi-bin/stable_token";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                    "grant_type", "client_credential",
                    "appid", wechatConfig.getAppid(),
                    "secret", wechatConfig.getSecret(),
                    "force_refresh", false
            )), headers);
            String response = restTemplate.postForObject(url, entity, String.class);
            Map<String, Object> result = objectMapper.readValue(response, Map.class);
            if (result.containsKey("errcode")) {
                log.error("获取微信access_token失败: {}", response);
                throw new RuntimeException("获取微信access_token失败: " + result.get("errmsg"));
            }
            accessToken = (String) result.get("access_token");
            int expiresIn = (Integer) result.getOrDefault("expires_in", 7200);
            tokenExpireAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
            log.info("微信access_token获取成功");
            return accessToken;
        } catch (Exception e) {
            log.error("获取微信access_token异常", e);
            throw new RuntimeException("获取微信access_token失败", e);
        }
    }

    /**
     * 生成小程序二维码（wxacode.getUnlimited）
     * @param scene 场景值（这里用 sessionId）
     * @return 二维码图片字节数组
     */
    public byte[] generateQrCode(String scene) {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=" + token;

        Map<String, Object> body = Map.of(
                "scene", scene,
                "page", "pages/verify/verify",
                "width", 280,
                "check_path", false
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(body), headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, byte[].class);

            byte[] result = response.getBody();
            if (result == null || result.length == 0) {
                log.error("生成小程序二维码返回为空, scene={}", scene);
                return null;
            }
            // 检查是否是错误信息（微信错误时返回JSON文本而非图片）
            String maybeJson = new String(result, java.nio.charset.StandardCharsets.UTF_8);
            if (maybeJson.startsWith("{")) {
                log.error("生成小程序二维码失败, scene={}: {}", scene, maybeJson);
                return null;
            }
            return result;
        } catch (Exception e) {
            log.error("生成小程序二维码异常, scene={}", scene, e);
            return null;
        }
    }
}
