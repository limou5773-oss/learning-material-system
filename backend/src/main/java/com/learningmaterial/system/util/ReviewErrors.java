package com.learningmaterial.system.util;

import com.learningmaterial.system.common.ApiException;
import org.springframework.http.HttpStatus;

/** 审核接口与业务层共用的错误约定。 */
public final class ReviewErrors {
    private ReviewErrors() { }

    public static void checkPage(int page,int size){if(page<1||size<1||size>100)throw invalid();}

    public static ApiException invalid(){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_INPUT","请检查输入字段");}

    public static ApiException notFound(){return new ApiException(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND","资料或审核记录不存在");}

    public static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"REVIEW_CONFLICT","资料版本或审核状态已变化，请刷新");}
}
