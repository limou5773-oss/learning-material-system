package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.PersonalMaterialMatch;
import java.util.List;

/** PersonalMaterialSearchService 的业务契约。 */
public interface PersonalMaterialSearchService {
    List<PersonalMaterialMatch> search(long userId, String keywords, String subject, int limit);

    void checkAvailable(long userId, List<PersonalMaterialMatch> matches);

    void lockMaterials(long userId, long primaryMaterialId, List<Long> cardIds);

    void lockAndCheck(long userId, List<PersonalMaterialMatch> matches);

}
