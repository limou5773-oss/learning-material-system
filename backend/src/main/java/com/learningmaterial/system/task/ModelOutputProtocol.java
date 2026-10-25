package com.learningmaterial.system.task;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.learningmaterial.system.task.AiTaskDtos.*;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The provider supplies only content and exact quotations. All identities and offsets are server
 * owned.
 */
@Component
public class ModelOutputProtocol {
    private final ObjectReader reader;

    public ModelOutputProtocol(ObjectMapper json) {
        reader =
                json.reader()
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public CandidateResult parse(
            String content,
            AiTaskRow task,
            TaskSourcePreparation.Prepared prepared,
            GenerationInfo info) {
        return parseInternal(content, task, prepared, info, false, List.of());
    }

    public CandidateResult parseAgent(
            String content,
            AiTaskRow task,
            TaskSourcePreparation.Prepared prepared,
            GenerationInfo info,
            List<PersonalMaterialSearchService.Match> matches) {
        return parseInternal(content, task, prepared, info, true, matches);
    }

    private CandidateResult parseInternal(
            String content,
            AiTaskRow task,
            TaskSourcePreparation.Prepared prepared,
            GenerationInfo info,
            boolean agent,
            List<PersonalMaterialSearchService.Match> matches) {
        try {
            if (content == null || content.length() > 200_000) invalid();
            JsonNode root = reader.readTree(content);
            if (agent) fields(root, "summary", "tags", "cards", "relations");
            else fields(root, "summary", "tags", "cards");
            String summary = text(root.get("summary"), 4000, false);
            List<String> tags = tags(root.get("tags"));
            var cards = root.get("cards");
            if (!cards.isArray() || cards.isEmpty() || cards.size() > 30) invalid();
            Map<String, MaterialChunkRow> allowed = new HashMap<>();
            for (var source : prepared.sources()) allowed.put(source.getId().toString(), source);
            List<CandidateCard> result = new ArrayList<>();
            for (var card : cards) {
                fields(card, "title", "content", "summary", "subject", "tags", "sources");
                String title = text(card.get("title"), 255, false),
                        body = text(card.get("content"), 8000, false);
                String shortText = text(card.get("summary"), 2000, true),
                        subject = text(card.get("subject"), 64, true);
                List<String> cardTags = tags(card.get("tags"));
                var sources = card.get("sources");
                if (!sources.isArray() || sources.isEmpty() || sources.size() > 20) invalid();
                List<SourceRef> refs = new ArrayList<>();
                Set<String> locators = new HashSet<>();
                for (var ref : sources) {
                    fields(ref, "chunkId", "quoteText");
                    String id = text(ref.get("chunkId"), 20, false);
                    String quote = text(ref.get("quoteText"), 2000, false);
                    var original = allowed.get(id);
                    if (original == null) invalid();
                    int start = original.getContent().indexOf(quote);
                    if (start < 0) invalid();
                    if (!locators.add(id + ":" + start + ":" + (start + quote.length()))) invalid();
                    refs.add(new SourceRef(id, quote, start, start + quote.length()));
                }
                result.add(
                        new CandidateCard(
                                "model-" + (result.size() + 1),
                                title,
                                body,
                                shortText,
                                subject,
                                cardTags,
                                List.copyOf(refs),
                                null));
            }
            var extraction = prepared.extraction();
            List<String> warnings = new ArrayList<>();
            warnings.add("真实模型生成；请核对原文。引用匹配不代表内容事实正确或引用足以支持结论。");
            if (extraction != null) warnings.addAll(extraction.warnings());
            return new CandidateResult(
                    task.getId().toString(),
                    1,
                    task.getMaterialVersion(),
                    "MODEL",
                    summary,
                    tags,
                    List.copyOf(result),
                    agent ? relations(root.get("relations"), result, matches) : List.of(),
                    extraction == null ? null : extraction.totalPages(),
                    extraction == null ? List.of() : extraction.processedPages(),
                    List.copyOf(warnings),
                    info);
        } catch (TaskExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TaskExecutionException("MODEL_OUTPUT_INVALID");
        }
    }

    private static List<CandidateRelation> relations(
            JsonNode node,
            List<CandidateCard> cards,
            List<PersonalMaterialSearchService.Match> matches) {
        if (node == null || !node.isArray() || node.size() > 10) invalid();
        Map<String, PersonalMaterialSearchService.Match> allowed = new HashMap<>();
        matches.forEach(match -> allowed.put(match.cardId(), match));
        List<CandidateRelation> result = new ArrayList<>();
        Set<String> edges = new HashSet<>();
        for (JsonNode relation : node) {
            fields(relation, "sourceIndex", "targetCardId", "evidenceText");
            JsonNode source = relation.get("sourceIndex");
            if (!source.isIntegralNumber()
                    || !source.canConvertToInt()
                    || source.intValue() < 0
                    || source.intValue() >= cards.size()) invalid();
            String target = text(relation.get("targetCardId"), 20, false);
            var match = allowed.get(target);
            if (match == null || !edges.add(source.intValue() + ":" + target)) invalid();
            String evidence = text(relation.get("evidenceText"), 2000, false);
            result.add(
                    new CandidateRelation(
                            "agent-rel-" + (result.size() + 1),
                            new CardEndpoint(
                                    cards.get(source.intValue()).candidateKey(), null, null),
                            new CardEndpoint(null, target, match.cardVersion()),
                            "RELATED",
                            evidence));
        }
        return List.copyOf(result);
    }

    private static void fields(JsonNode node, String... expected) {
        if (node == null || !node.isObject() || node.size() != expected.length) invalid();
        for (String name : expected) if (!node.has(name)) invalid();
    }

    private static String text(JsonNode node, int limit, boolean nullable) {
        if (nullable && node != null && node.isNull()) return null;
        if (node == null
                || !node.isTextual()
                || node.asText().isBlank()
                || node.asText().length() > limit) invalid();
        return node.asText();
    }

    private static List<String> tags(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 10) invalid();
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var tag : node) {
            String value = text(tag, 64, false).trim();
            if (!seen.add(value.toLowerCase(Locale.ROOT))) invalid();
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void invalid() {
        throw new TaskExecutionException("MODEL_OUTPUT_INVALID");
    }
}
