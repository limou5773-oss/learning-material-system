package com.learningmaterial.system.card;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CardDtos {
    private CardDtos() { }

    public record CardInput(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 8000) String content,
        @Size(max = 2000) String summary,
        @Size(max = 64) String subject,
        @Size(max = 10) List<@NotBlank @Size(max = 64) String> tags
    ) { }

    public static final class CardPatch {
        @NotNull @Min(1) private Integer version;
        @Size(max = 255) private String title;
        @Size(max = 8000) private String content;
        @Size(max = 2000) private String summary;
        @Size(max = 64) private String subject;
        @Size(max = 10) private List<@NotBlank @Size(max = 64) String> tags;
        private boolean titlePresent, contentPresent, summaryPresent, subjectPresent, tagsPresent;

        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public String getTitle() { return title; }
        public void setTitle(String title) { titlePresent = true; this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { contentPresent = true; this.content = content; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { summaryPresent = true; this.summary = summary; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { subjectPresent = true; this.subject = subject; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { tagsPresent = true; this.tags = tags; }
        public boolean hasTitle() { return titlePresent; }
        public boolean hasContent() { return contentPresent; }
        public boolean hasSummary() { return summaryPresent; }
        public boolean hasSubject() { return subjectPresent; }
        public boolean hasTags() { return tagsPresent; }
        public boolean hasChanges() { return titlePresent || contentPresent || summaryPresent || subjectPresent || tagsPresent; }
    }
}
