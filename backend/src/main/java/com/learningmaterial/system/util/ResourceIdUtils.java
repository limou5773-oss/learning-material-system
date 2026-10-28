package com.learningmaterial.system.util;

import com.learningmaterial.system.common.ApiException;
import org.springframework.http.HttpStatus;

/** API 资源 ID 的解析规则。 */
public final class ResourceIdUtils {
    private ResourceIdUtils() { }

    public static long parseId(String id) {
        if (id != null && id.matches("[1-9][0-9]*")) {
            try { return Long.parseLong(id); } catch (NumberFormatException ignored) { }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误");
    }
}
