package com.learningmaterial.system.card;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class CardLayoutDtos {
    private CardLayoutDtos() { }

    public record LayoutItem(
        @NotBlank @JsonDeserialize(using = CardOrganizationJson.StringValue.class) String cardId,
        @NotNull @DecimalMin("-100000") @DecimalMax("100000")
        @JsonDeserialize(using = CardOrganizationJson.DecimalValue.class) BigDecimal x,
        @NotNull @DecimalMin("-100000") @DecimalMax("100000")
        @JsonDeserialize(using = CardOrganizationJson.DecimalValue.class) BigDecimal y,
        @NotNull @Min(0) @JsonDeserialize(using = CardOrganizationJson.IntegerValue.class) Integer orderNo
    ) { }

    public record LayoutInput(
        @NotNull @Min(0) @JsonDeserialize(using = CardOrganizationJson.IntegerValue.class) Integer layoutVersion,
        @NotNull @Size(max = 200) List<@NotNull @Valid LayoutItem> items
    ) { }

    public record LayoutView(String layoutKey, int layoutVersion, List<LayoutItem> items) { }
}
