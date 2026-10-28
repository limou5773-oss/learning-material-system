package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.MaterialDtos.MaterialPatch;
import com.learningmaterial.system.dto.MaterialDtos.MaterialView;
import com.learningmaterial.system.dto.MaterialDtos.NoteInput;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.dto.MaterialDtos.TagView;
import com.learningmaterial.system.dto.StoredFile;
import org.springframework.web.multipart.MultipartFile;

/** MaterialService 的业务契约。 */
public interface MaterialService {
    MaterialView createNote(long userId, NoteInput input);

    MaterialView uploadFile(long userId, MultipartFile file, String titleInput, String subjectInput,
                                   String tagsJson, boolean allowDuplicate);

    StoredFile readFile(long userId, String id);

    MaterialView get(long userId, String id);

    MaterialView patch(long userId, String id, MaterialPatch patch);

    void delete(long userId, String id, int version);

    PageView<MaterialView> list(long userId, String keyword, String subject, String tagId,
                                        String status, boolean favoriteOnly, int page, int pageSize);

    PageView<TagView> listTags(long userId, String keyword, int page, int pageSize);

}
