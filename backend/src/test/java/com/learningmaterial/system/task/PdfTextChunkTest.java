package com.learningmaterial.system.task;

import com.learningmaterial.system.dto.SourceDraft;
import com.learningmaterial.system.service.impl.PdfTextExtractionServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextChunkTest {
    @Test void supplementaryCharactersStayWholeAtChunkBoundary() {
        String text = "A".repeat(1999) + "\uD83D\uDE00" + "B".repeat(2000);
        var parts = PdfTextExtractionServiceImpl.splitPage(7, text);
        assertThat(parts).hasSize(3);
        assertThat(parts.getFirst().content()).hasSize(1999);
        assertThat(parts.get(1).content()).startsWith("\uD83D\uDE00");
        assertThat(parts.stream().map(SourceDraft::content).reduce("", String::concat)).isEqualTo(text);
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.content().length()).isLessThanOrEqualTo(2000);
            assertThat(Character.isHighSurrogate(p.content().charAt(p.content().length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(p.content().charAt(0))).isFalse();
        });
    }
}
