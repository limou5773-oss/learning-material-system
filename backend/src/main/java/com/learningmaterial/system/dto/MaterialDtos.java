package com.learningmaterial.system.dto;

import com.learningmaterial.system.entity.MaterialRow;
import com.learningmaterial.system.entity.TagRow;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public final class MaterialDtos {
    private MaterialDtos() { }

    public enum NoteType { NOTE }

    public record NoteInput(
        @NotNull NoteType type,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 64) String subject,
        @Size(max = 10) List<@NotBlank @Size(max = 64) String> tags,
        @NotBlank @Size(max = 20000) String noteText
    ) { }

    public static final class MaterialPatch {
        @NotNull @Min(1) private Integer version;
        @Size(max = 255) private String title;
        @Size(max = 64) private String subject;
        @Size(max = 10) private List<@NotBlank @Size(max = 64) String> tags;
        @Size(max = 20000) private String noteText;
        @Size(max = 4000) private String summary;
        private String status;
        private boolean titlePresent, subjectPresent, tagsPresent, noteTextPresent, summaryPresent, statusPresent;

        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public String getTitle() { return title; }
        public void setTitle(String title) { titlePresent = true; this.title = title; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { subjectPresent = true; this.subject = subject; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { tagsPresent = true; this.tags = tags; }
        public String getNoteText() { return noteText; }
        public void setNoteText(String noteText) { noteTextPresent = true; this.noteText = noteText; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { summaryPresent = true; this.summary = summary; }
        public String getStatus() { return status; }
        public void setStatus(String status) { statusPresent = true; this.status = status; }
        public boolean hasTitle() { return titlePresent; }
        public boolean hasSubject() { return subjectPresent; }
        public boolean hasTags() { return tagsPresent; }
        public boolean hasNoteText() { return noteTextPresent; }
        public boolean hasSummary() { return summaryPresent; }
        public boolean hasStatus() { return statusPresent; }
        public boolean hasChanges() {
            return titlePresent || subjectPresent || tagsPresent || noteTextPresent || summaryPresent || statusPresent;
        }
    }

    public record TagView(String id, String name) {
        public static TagView from(TagRow row) { return new TagView(row.getId().toString(), row.getName()); }
    }

    public record MaterialView(
        String id, String title, String subject, String type, String summary,
        String noteText, List<TagView> tags, int version, String status,
        String accessState, Long fileSize, Integer pageCount, boolean favorite,
        Instant createdAt, Instant updatedAt
    ) {
        public static MaterialView from(MaterialRow row, List<TagRow> tags, boolean favorite) {
            return new MaterialView(row.getId().toString(), row.getTitle(), row.getSubject(),
                row.getMaterialType(), row.getSummary(), row.getNoteText(),
                tags.stream().map(TagView::from).toList(), row.getVersion(), row.getStatus(),
                row.getAccessState(), row.getFileSize(), row.getPageCount(), favorite,
                row.getCreatedAt().toInstant(ZoneOffset.UTC),
                row.getUpdatedAt().toInstant(ZoneOffset.UTC));
        }
    }

    public record PageView<T>(List<T> items, int page, int pageSize, long total) { }
}
