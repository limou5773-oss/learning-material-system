package com.learningmaterial.system.card;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CardRelationDtos {
    private CardRelationDtos() { }

    public record RelationInput(
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") @Size(max = 19)
        @JsonDeserialize(using = CardOrganizationJson.StringValue.class) String sourceCardId,
        @NotNull @Min(1) @JsonDeserialize(using = CardOrganizationJson.IntegerValue.class) Integer sourceCardVersion,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") @Size(max = 19)
        @JsonDeserialize(using = CardOrganizationJson.StringValue.class) String targetCardId,
        @NotNull @Min(1) @JsonDeserialize(using = CardOrganizationJson.IntegerValue.class) Integer targetCardVersion,
        @NotBlank @Pattern(regexp = "RELATED|PREREQUISITE|EXAMPLE")
        @JsonDeserialize(using = CardOrganizationJson.StringValue.class) String type,
        @NotBlank @Size(max = 2000)
        @JsonDeserialize(using = CardOrganizationJson.StringValue.class) String evidenceText
    ) { }

    public record RelationView(String id, String sourceCardId, String targetCardId, String type, String evidenceText) { }
    public record GraphView(List<CardService.CardView> nodes, List<RelationView> edges, boolean hasMore) { }
}
