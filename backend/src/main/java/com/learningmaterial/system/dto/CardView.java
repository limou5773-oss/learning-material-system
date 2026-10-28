package com.learningmaterial.system.dto;

import java.time.Instant;
import java.util.List;

public record CardView(String id, String title, String content, String summary, List<String> tags,
                       String subject, String parentCardId, int version, String origin,
                       String generationMode, boolean isEdited, boolean favorite, Instant createdAt, Instant updatedAt) { }
