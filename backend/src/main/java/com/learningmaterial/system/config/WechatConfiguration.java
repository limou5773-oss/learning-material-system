package com.learningmaterial.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.integration.wechat.WechatCode2SessionClient;
import com.learningmaterial.system.integration.wechat.WechatLoginClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WechatConfiguration {
    @Bean WechatLoginClient wechatLoginClient(@Value("${lms.wechat.enabled:false}") boolean enabled,
        @Value("${lms.wechat.app-id:}") String appId, @Value("${lms.wechat.app-secret:}") String appSecret,
        ObjectMapper json) {
        return new WechatCode2SessionClient(enabled, appId, appSecret, json);
    }
}
