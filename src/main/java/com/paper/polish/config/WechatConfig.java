package com.paper.polish.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat")
public class WechatConfig {
    private String appid = "wx100d25899c7b6812";
    private String secret = "6385971389d6d7a2e150c89ae5860bb4";
}
