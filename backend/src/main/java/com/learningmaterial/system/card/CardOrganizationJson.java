package com.learningmaterial.system.card;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/** The organization API accepts JSON numbers and strings without scalar coercion. */
public final class CardOrganizationJson {
    private CardOrganizationJson() { }

    public static final class IntegerValue extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            return parser.getIntValue();
        }
    }

    public static final class DecimalValue extends JsonDeserializer<BigDecimal> {
        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT))
                return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
            return parser.getDecimalValue();
        }
    }

    public static final class StringValue extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING))
                return (String) context.handleUnexpectedToken(String.class, parser);
            return parser.getText();
        }
    }
}
