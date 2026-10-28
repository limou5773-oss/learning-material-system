package com.learningmaterial.system.dto;

import java.nio.file.Path;

public record StoredFile(Path path, String mimeType, long size) { }
