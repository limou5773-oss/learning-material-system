package com.learningmaterial.system.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/** The mock adapter accepts only server-built calls with the exact documented arguments. */
final class MockToolProtocol {
    private MockToolProtocol() { }

    static void validate(String name, JsonNode args, AiTaskRow task) {
        if (task == null || args == null || !args.isObject()) invalid();
        switch (name) {
            case "prepare_source" -> {
                fields(args, Set.of("materialId", "materialVersion"));
                if (!task.getMaterialId().toString().equals(args.path("materialId").textValue())
                    || !args.path("materialVersion").isIntegralNumber()
                    || args.path("materialVersion").intValue() != task.getMaterialVersion()) invalid();
            }
            case "generate_summary_cards" -> {
                fields(args, Set.of("goalType", "chunkIds"));
                JsonNode ids = args.path("chunkIds");
                if (!task.getGoalType().equals(args.path("goalType").textValue())
                    || !ids.isArray() || ids.isEmpty() || ids.size() > 3) invalid();
                Set<String> seen = new HashSet<>();
                for (JsonNode id : ids) {
                    if (!id.isTextual() || !id.textValue().matches("[1-9][0-9]*")
                        || !seen.add(id.textValue())) invalid();
                }
            }
            case "validate_candidate" -> {
                fields(args, Set.of("taskId", "resultVersion", "sourceVersion"));
                if (!task.getId().toString().equals(args.path("taskId").textValue())
                    || !args.path("resultVersion").isIntegralNumber()
                    || args.path("resultVersion").intValue() != 1
                    || !args.path("sourceVersion").isIntegralNumber()
                    || args.path("sourceVersion").intValue() != task.getMaterialVersion()) invalid();
            }
            default -> invalid();
        }
    }

    private static void fields(JsonNode args, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        args.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) invalid();
    }

    private static void invalid() { throw new TaskExecutionException("INVALID_TOOL_ARGUMENTS"); }
}
