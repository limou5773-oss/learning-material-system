package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.ContentReviewDtos.ReviewContent;
import com.learningmaterial.system.dto.ContentReviewDtos.ReviewView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.dto.StoredFile;
import com.learningmaterial.system.security.AuthPrincipal;

/** ContentReviewService 的业务契约。 */
public interface ContentReviewService {
    StoredFile file(AuthPrincipal actor,long id);

    ReviewContent content(AuthPrincipal actor,long id,String reason);

    ReviewView decide(AuthPrincipal actor,long id,int version,String decision,String reason);

    ReviewView submit(long owner,long id,int version,String reason);

    PageView<ReviewView> history(long owner,long id,int page,int size);

    PageView<ReviewView> list(String status,int page,int size);

}
