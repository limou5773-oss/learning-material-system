package com.learningmaterial.system.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.MaterialDtos.MaterialPatch;
import com.learningmaterial.system.dto.MaterialDtos.MaterialView;
import com.learningmaterial.system.dto.MaterialDtos.NoteInput;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.dto.MaterialDtos.TagView;
import com.learningmaterial.system.dto.StoredFile;
import com.learningmaterial.system.entity.MaterialRow;
import com.learningmaterial.system.entity.TagRow;
import com.learningmaterial.system.integration.storage.FileStorage;
import com.learningmaterial.system.mapper.AiTaskMapper;
import com.learningmaterial.system.mapper.AiToolCallMapper;
import com.learningmaterial.system.mapper.FavoriteMapper;
import com.learningmaterial.system.mapper.MaterialMapper;
import com.learningmaterial.system.mapper.ModelAttemptMapper;
import com.learningmaterial.system.mapper.OcrAttemptMapper;
import com.learningmaterial.system.mapper.TagMapper;
import com.learningmaterial.system.service.MaterialService;
import com.learningmaterial.system.util.ImageValidator;
import com.learningmaterial.system.util.PdfValidator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MaterialServiceImpl implements MaterialService {
    private final MaterialMapper materials;
    private final TagMapper tags;
    private final Clock clock;
    private final FileStorage storage;
    private final ImageValidator imageValidator;
    private final PdfValidator pdfValidator;
    private final FavoriteMapper favorites;
    private final AiTaskMapper tasks;
    private final AiToolCallMapper toolCalls;
    private final OcrAttemptMapper ocrAttempts;
    private final ModelAttemptMapper modelAttempts;
    private final ObjectMapper objectMapper;
    private final com.learningmaterial.system.service.ContentReviewService reviews;

    public MaterialServiceImpl(MaterialMapper materials, TagMapper tags, Clock clock,
                           FileStorage storage, ImageValidator imageValidator, PdfValidator pdfValidator,
                           FavoriteMapper favorites, AiTaskMapper tasks, AiToolCallMapper toolCalls,
                           ObjectMapper objectMapper, OcrAttemptMapper ocrAttempts, ModelAttemptMapper modelAttempts,
                           com.learningmaterial.system.service.ContentReviewService reviews) {
        this.reviews = reviews;
        this.materials = materials;
        this.tags = tags;
        this.clock = clock;
        this.storage = storage;
        this.imageValidator = imageValidator;
        this.pdfValidator = pdfValidator;
        this.favorites = favorites;
        this.tasks = tasks;
        this.toolCalls = toolCalls;
        this.ocrAttempts = ocrAttempts; this.modelAttempts = modelAttempts;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public MaterialView createNote(long userId, NoteInput input) {
        String title = input.title().trim();
        if (title.isEmpty()) throw invalid("INVALID_TITLE", "标题不能为空");
        if (input.noteText().trim().isEmpty()) throw invalid("INVALID_NOTE", "笔记正文不能为空");
        String subject = optional(input.subject(), 64, "学科");
        List<String> names = input.tags() == null ? List.of() : input.tags().stream().map(String::trim).toList();
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            if (name.isEmpty()) throw invalid("INVALID_TAG", "标签不能为空");
            if (!unique.add(name.toLowerCase(Locale.ROOT))) {
                throw invalid("DUPLICATE_TAG", "标签不能重复");
            }
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        MaterialRow row = new MaterialRow();
        row.setUserId(userId);
        row.setTitle(title);
        row.setSubject(subject);
        row.setMaterialType("NOTE");
        row.setNoteText(input.noteText());
        row.setVersion(1);
        row.setStatus("ACTIVE");
        row.setAccessState("NORMAL");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        materials.insert(row);

        Set<Long> attached = new HashSet<>();
        for (String name : names) {
            tags.insertOrGet(userId, name, now);
            TagRow tag = tags.findOwnedByName(userId, name);
            if (!attached.add(tag.getId())) throw invalid("DUPLICATE_TAG", "标签不能重复");
            tags.attach(row.getId(), tag.getId(), now);
        }
        return view(row);
    }

    @Override
    @Transactional
    public MaterialView uploadFile(long userId, MultipartFile file, String titleInput, String subjectInput,
                                   String tagsJson, boolean allowDuplicate) {
        if (titleInput == null || titleInput.trim().isEmpty() || titleInput.trim().length() > 255) {
            throw invalid("INVALID_TITLE", "标题不能为空且不能超过255字");
        }
        String subject = optional(subjectInput, 64, "学科");
        List<String> names = parseTagsJson(tagsJson);
        CheckedFile checked = checkFile(file);
        MaterialRow duplicate = materials.findOwnedByHash(userId, checked.hash());
        if (duplicate != null && !allowDuplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_FILE", "该文件已上传，请确认是否重复保存",
                duplicate.getId().toString());
        }
        String key = storage.store(checked.bytes(), checked.extension());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.deleteQuietly(key);
            }
        });
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        MaterialRow row = new MaterialRow();
        row.setUserId(userId);
        row.setTitle(titleInput.trim());
        row.setSubject(subject);
        row.setMaterialType(checked.materialType());
        row.setStorageKey(key);
        row.setMimeType(checked.mime());
        row.setFileSize((long) checked.bytes().length);
        row.setFileHash(checked.hash());
        row.setPageCount(checked.pageCount());
        row.setVersion(1);
        row.setStatus("ACTIVE");
        row.setAccessState("NORMAL");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        materials.insertFile(row);
        attachTags(userId, row.getId(), names, now);
        return view(row);
    }

    @Override
    @Transactional(readOnly = true)
    public StoredFile readFile(long userId, String id) {
        MaterialRow row = materials.findOwned(parseId(id), userId);
        if (row == null || row.getStorageKey() == null) throw notFound();
        return new StoredFile(storage.open(row.getStorageKey()), row.getMimeType(), row.getFileSize());
    }

    private CheckedFile checkFile(MultipartFile file) {
        String filename = file == null ? "" : file.getOriginalFilename();
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            PdfValidator.CheckedPdf pdf = pdfValidator.check(file);
            return new CheckedFile(pdf.bytes(), "application/pdf", "pdf", pdf.hash(), pdf.pages(), "PDF");
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
            ImageValidator.CheckedImage image = imageValidator.check(file);
            return new CheckedFile(image.bytes(), image.mime(), image.extension(), image.hash(), null, "IMAGE");
        }
        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "仅支持JPEG、PNG或PDF文件");
    }

    private record CheckedFile(byte[] bytes, String mime, String extension, String hash,
                               Integer pageCount, String materialType) { }

    @Override
    @Transactional(readOnly = true)
    public MaterialView get(long userId, String id) {
        MaterialRow row = materials.findOwned(parseId(id), userId);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "资料不存在");
        return view(row);
    }

    @Override
    @Transactional
    public MaterialView patch(long userId, String id, MaterialPatch patch) {
        long materialId = parseId(id);
        MaterialRow current = materials.findOwnedForUpdate(materialId, userId);
        if (current == null) throw notFound();
        boolean note = "NOTE".equals(current.getMaterialType());
        if (!note && patch.hasNoteText()) throw invalid("UNSUPPORTED_NOTE_TEXT", "文件资料不能填写笔记正文");
        if (current.getVersion() != patch.getVersion()) throw conflict(current.getVersion());
        if (!patch.hasChanges()) throw invalid("EMPTY_PATCH", "请提供要修改的内容");
        String title = patch.hasTitle() ? patch.getTitle() : current.getTitle();
        String noteText = patch.hasNoteText() ? patch.getNoteText() : current.getNoteText();
        if (title == null || title.trim().isEmpty()) throw invalid("INVALID_TITLE", "标题不能为空");
        if (title.length() > 255) throw invalid("INVALID_TITLE", "标题长度超限");
        if (note && (noteText == null || noteText.trim().isEmpty())) throw invalid("INVALID_NOTE", "笔记正文不能为空");
        if (note && noteText.length() > 20000) throw invalid("INVALID_NOTE", "笔记正文长度超限");
        String subject = patch.hasSubject() ? optional(patch.getSubject(), 64, "学科") : current.getSubject();
        String summary = patch.hasSummary() ? optional(patch.getSummary(), 4000, "摘要") : current.getSummary();
        String status = patch.hasStatus() ? patch.getStatus() : current.getStatus();
        if (status == null || !List.of("ACTIVE", "ARCHIVED").contains(status)) throw invalid("INVALID_STATUS", "资料状态无效");
        List<String> names = patch.hasTags() ? normalizedTags(patch.getTags()) : null;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int changed = materials.updateConditional(materialId, userId, patch.getVersion(), title.trim(),
            subject, noteText, summary, status, now);
        if (changed == 0) throw failedVersion(userId, materialId);
        // 审核结论绑定的是正文内容：只有正文或摘要变化才使已通过的审核失效，
        // 修改标题/学科/标签或归档恢复不影响已通过的审核结论；
        // 但版本号变化会让待审单无法再被处置，因此元数据变更时清理未处置的送审单。
        boolean contentChanged = !Objects.equals(noteText, current.getNoteText())
            || !Objects.equals(summary, current.getSummary());
        if (contentChanged) reviews.supersedeByMaterial(userId, materialId, now);
        else reviews.supersedePendingByMaterial(materialId, now);
        if (names != null) {
            tags.detachAll(materialId);
            attachTags(userId, materialId, names, now);
        }
        return get(userId, id);
    }

    @Override
    @Transactional
    public void delete(long userId, String id, int version) {
        if (version < 1) throw invalid("INVALID_VERSION", "版本号无效");
        long materialId = parseId(id);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (materials.softDeleteConditional(materialId, userId, version, now) == 0) {
            throw failedVersion(userId, materialId);
        }
        favorites.removeMaterial(userId, materialId);
        reviews.supersedeByMaterial(userId, materialId, now);
        tasks.cancelForDeletedMaterial(userId, materialId, now);
        toolCalls.markDeletedSourceUnknown(userId, materialId, now);
        ocrAttempts.markDeletedSourceUnknown(userId, materialId, now);
        modelAttempts.markDeletedSourceUnknown(userId, materialId, now);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<MaterialView> list(long userId, String keyword, String subject, String tagId,
                                        String status, boolean favoriteOnly, int page, int pageSize) {
        checkPage(page, pageSize);
        String normalizedStatus = status == null ? "ACTIVE" : status;
        if (!List.of("ACTIVE", "ARCHIVED", "ALL").contains(normalizedStatus)) {
            throw invalid("INVALID_STATUS", "资料状态无效");
        }
        String term = optional(keyword, 100, "关键词");
        String category = optional(subject, 64, "学科");
        Long tag = tagId == null || tagId.isBlank() ? null : parseId(tagId);
        long offset = (long) (page - 1) * pageSize;
        long total = materials.countOwned(userId, normalizedStatus, term, category, tag, favoriteOnly);
        List<MaterialView> items = materials.listOwned(userId, normalizedStatus, term, category, tag, favoriteOnly, pageSize, offset)
            .stream().map(this::view).toList();
        return new PageView<>(items, page, pageSize, total);
    }

    private MaterialView view(MaterialRow row) {
        return MaterialView.from(row, tags.forMaterial(row.getId()),
            favorites.hasMaterial(row.getUserId(), row.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<TagView> listTags(long userId, String keyword, int page, int pageSize) {
        checkPage(page, pageSize);
        String term = optional(keyword, 100, "关键词");
        long offset = (long) (page - 1) * pageSize;
        long total = tags.countOwned(userId, term);
        List<TagView> items = tags.listOwned(userId, term, pageSize, offset)
            .stream().map(TagView::from).toList();
        return new PageView<>(items, page, pageSize, total);
    }

    private static String optional(String value, int maxLength, String field) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw invalid("INVALID_INPUT", field + "长度超限");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> normalizedTags(List<String> input) {
        if (input == null || input.size() > 10) throw invalid("INVALID_TAG", "标签格式无效");
        List<String> names = input.stream().map(name -> name == null ? "" : name.trim()).toList();
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            if (name.isEmpty() || name.length() > 64) throw invalid("INVALID_TAG", "标签格式无效");
            if (!unique.add(name.toLowerCase(Locale.ROOT))) throw invalid("DUPLICATE_TAG", "标签不能重复");
        }
        return names;
    }

    private List<String> parseTagsJson(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return List.of();
        try {
            JsonNode value = objectMapper.readTree(tagsJson);
            if (!value.isArray() || value.size() > 10) throw invalid("INVALID_TAG", "标签格式无效");
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            for (JsonNode item : value) {
                if (!item.isTextual()) throw invalid("INVALID_TAG", "标签格式无效");
                names.add(item.asText());
            }
            return normalizedTags(names);
        } catch (JsonProcessingException ex) {
            throw invalid("INVALID_TAG", "标签格式无效");
        }
    }

    private void attachTags(long userId, long materialId, List<String> names, LocalDateTime now) {
        Set<Long> attached = new HashSet<>();
        for (String name : names) {
            tags.insertOrGet(userId, name, now);
            TagRow tag = tags.findOwnedByName(userId, name);
            if (!attached.add(tag.getId())) throw invalid("DUPLICATE_TAG", "标签不能重复");
            tags.attach(materialId, tag.getId(), now);
        }
    }

    private ApiException failedVersion(long userId, long materialId) {
        MaterialRow latest = materials.findOwned(materialId, userId);
        return latest == null ? notFound() : conflict(latest.getVersion());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "资料不存在");
    }

    private static ApiException conflict(int currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资料已被更新，请查看最新版本", currentVersion);
    }

    private static long parseId(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误");
        }
        try { return Long.parseLong(id); }
        catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误");
        }
    }

    private static void checkPage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("INVALID_PAGE", "分页参数无效");
        }
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
