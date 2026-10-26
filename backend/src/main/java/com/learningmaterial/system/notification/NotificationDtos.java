package com.learningmaterial.system.notification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;

public final class NotificationDtos {
    private NotificationDtos() { }

    @JsonDeserialize(using = ReadStateValue.class)
    public record ReadStateInput(@NotNull Boolean isRead) { }
    public record Target(String type, String taskId) { }
    public record NotificationView(String id, String type, String content, Target target,
                                   boolean isRead, Instant createdAt, String channelStatus) {
        static NotificationView from(NotificationRow row) {
            return new NotificationView(row.getId().toString(), row.getType(), row.getContent(),
                new Target("AI_TASK", row.getTaskId().toString()), row.getReadAt() != null,
                row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getChannelStatus());
        }
    }

    public static final class ReadStateValue extends JsonDeserializer<ReadStateInput> {
        @Override public ReadStateInput deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.START_OBJECT))
                return (ReadStateInput) context.handleUnexpectedToken(ReadStateInput.class, parser);
            JsonNode object = parser.getCodec().readTree(parser);
            if (object.size() > 1 || object.size() == 1 && !object.has("isRead"))
                return context.reportInputMismatch(ReadStateInput.class, "请求只能包含isRead字段");
            JsonNode value = object.get("isRead");
            if (value != null && !value.isNull() && !value.isBoolean())
                return context.reportInputMismatch(ReadStateInput.class, "isRead必须是JSON布尔值");
            if (parser.nextToken() != null)
                return context.reportInputMismatch(ReadStateInput.class, "请求只能包含一个JSON对象");
            return new ReadStateInput(value == null || value.isNull() ? null : value.booleanValue());
        }
    }
}
