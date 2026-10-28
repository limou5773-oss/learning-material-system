package com.learningmaterial.system.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.learningmaterial.system.entity.NotificationRow;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;

public final class NotificationDtos {
    private NotificationDtos() { }

    @JsonDeserialize(using = ReadStateValue.class)
    public record ReadStateInput(@NotNull Boolean isRead) { }
    public sealed interface NotificationTarget permits Target,MaterialTarget { }
    public record Target(String type, String taskId) implements NotificationTarget { }
    public record MaterialTarget(String type,String materialId,String reviewId) implements NotificationTarget { }
    public record NotificationView(String id, String type, String content, NotificationTarget target,
                                   boolean isRead, Instant createdAt, String channelStatus) {
        public static NotificationView from(NotificationRow row, ObjectMapper json) {
            return new NotificationView(row.getId().toString(), row.getType(), row.getContent(),
                target(row, json), row.getReadAt() != null,
                row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getChannelStatus());
        }
        private static NotificationTarget target(NotificationRow row, ObjectMapper json) {
            if(row.getReviewId()==null)return new Target("AI_TASK",row.getTaskId().toString());
            try {
                JsonNode stored=json.readTree(row.getTargetJson());
                return new MaterialTarget("MATERIAL",stored.path("materialId").asText(),row.getReviewId().toString());
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException("通知目标无法读取",ex);}
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
