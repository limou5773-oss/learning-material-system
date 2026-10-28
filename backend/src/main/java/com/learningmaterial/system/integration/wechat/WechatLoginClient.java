package com.learningmaterial.system.integration.wechat;

import com.learningmaterial.system.dto.WechatIdentity;

public interface WechatLoginClient {
    WechatIdentity exchange(String code);
}
