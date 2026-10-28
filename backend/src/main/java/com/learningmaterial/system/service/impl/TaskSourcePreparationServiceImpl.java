package com.learningmaterial.system.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningmaterial.system.dto.OcrExtractionOutcome;
import com.learningmaterial.system.dto.PdfExtraction;
import com.learningmaterial.system.dto.PreparedTaskSource;
import com.learningmaterial.system.dto.SourceDraft;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.entity.AiTaskRow;
import com.learningmaterial.system.entity.MaterialChunkRow;
import com.learningmaterial.system.integration.ocr.OcrSettings;
import com.learningmaterial.system.service.OcrExtractionService;
import com.learningmaterial.system.service.PdfTextExtractionService;
import com.learningmaterial.system.service.TaskCandidateService;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.service.TaskSourcePreparationService;
import com.learningmaterial.system.task.MockToolProtocol;
import com.learningmaterial.system.task.TaskExecutionException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TaskSourcePreparationServiceImpl implements TaskSourcePreparationService {
    private final TaskLeaseService leases;
    private final TaskCandidateService candidates;
    private final ObjectMapper json;
    private final PdfTextExtractionService pdfText;
    private final OcrExtractionService ocr;
    private final OcrSettings ocrSettings;

    public TaskSourcePreparationServiceImpl(
            TaskLeaseService leases,
            TaskCandidateService candidates,
            ObjectMapper json,
            PdfTextExtractionService pdfText,
            OcrExtractionService ocr,
            OcrSettings ocrSettings) {
        this.leases = leases;
        this.candidates = candidates;
        this.json = json;
        this.pdfText = pdfText;
        this.ocr = ocr;
        this.ocrSettings = ocrSettings;
    }

    @Override
    public PreparedTaskSource prepare(
            TaskLease lease, AiTaskRow task, JsonNode config, boolean model) {
        if (model
                && "IMAGE".equals(config.path("inputSnapshot").path("type").asText())
                && !ocrSettings.enabled())
            throw new TaskExecutionException("MODEL_SOURCE_TEXT_REQUIRED");
        ObjectNode prepareArgs =
                json.createObjectNode()
                        .put("materialId", task.getMaterialId().toString())
                        .put("materialVersion", task.getMaterialVersion());
        boolean pdf = "PDF".equals(config.path("inputSnapshot").path("type").asText());
        boolean imageOcr =
                ocrSettings.enabled()
                        && "IMAGE".equals(config.path("inputSnapshot").path("type").asText());
        String prepareTool =
                pdf ? "extract_pdf_text" : imageOcr ? "recognize_image" : "prepare_source";
        if (pdf) prepareArgs.set("pageRange", config.path("pageRange"));
        MockToolProtocol.validate(prepareTool, prepareArgs, task);
        Long prepareCall = leases.startStep(lease, prepareTool, imageOcr ? "OCR" : "LOCAL");
        if (prepareCall == null) return null;
        List<MaterialChunkRow> sources;
        PdfExtraction extraction = null;
        OcrExtractionOutcome recognized = null;
        try {
            if (pdf) {
                extraction = pdfText.extract(lease);
                if (extraction == null) return null;
                sources = extraction.sources();
                if (sources.isEmpty() && !ocrSettings.enabled())
                    throw new TaskExecutionException("PDF_OCR_REQUIRED");
            } else if (imageOcr) {
                recognized = ocr.extract(lease, null);
                if (recognized == null) return null;
                extraction = recognized.extraction();
                sources = extraction.sources();
            } else
                sources =
                        candidates.saveSources(lease, prepare(config.path("inputSnapshot"), model));
        } catch (TaskExecutionException ex) {
            String explanation =
                    switch (ex.errorCode()) {
                        case "PDF_OCR_REQUIRED" -> "所选页没有可提取文字，需要OCR或为空白页；未生成候选。";
                        case "PDF_TEXT_LIMIT_EXCEEDED" -> "所选页文字超过处理上限，请缩小页段后重试；未截断文字。";
                        default -> "来源文字提取未完成，请检查资料后重试。";
                    };
            leases.completeStep(
                    lease,
                    prepareCall,
                    false,
                    imageOcr ? ocr.attemptSummary(lease) : explanation,
                    ex.errorCode());
            throw ex;
        }
        if (sources == null
                || !leases.completeStep(
                        lease,
                        prepareCall,
                        true,
                        recognized != null
                                ? recognized.summary()
                                : sources.size()
                                        + (pdf
                                                ? "个PDF文字层来源片段"
                                                : (model ? "个可核验来源片段" : "个可核验的模拟来源片段")),
                        null)) return null;
        if (pdf
                && ocrSettings.enabled()
                && extraction != null
                && !extraction.needsOcrPages().isEmpty()) {
            Long ocrCall = leases.startStep(lease, "recognize_image", "OCR");
            if (ocrCall == null) return null;
            try {
                recognized = ocr.extract(lease, extraction);
                if (recognized == null) return null;
                extraction = recognized.extraction();
                sources = extraction.sources();
            } catch (TaskExecutionException ex) {
                leases.completeStep(
                        lease, ocrCall, false, ocr.attemptSummary(lease), ex.errorCode());
                throw ex;
            }
            if (!leases.completeStep(lease, ocrCall, true, recognized.summary(), null)) return null;
        }

        return new PreparedTaskSource(sources, extraction);
    }

    /**
     * Agent dispatches extraction and OCR independently so PDF feedback can inform its next plan.
     */
    @Override
    public PreparedTaskSource prepareAgent(
            TaskLease lease, JsonNode config, String tool, PreparedTaskSource prior) {
        return switch (tool) {
            case "prepare_source" -> {
                var sources =
                        candidates.saveSources(lease, prepare(config.path("inputSnapshot"), true));
                yield sources == null ? null : new PreparedTaskSource(sources, null);
            }
            case "extract_pdf_text" -> {
                var extraction = pdfText.extract(lease);
                yield extraction == null ? null : new PreparedTaskSource(extraction.sources(), extraction);
            }
            case "recognize_image" -> {
                if (!ocrSettings.enabled()) throw new TaskExecutionException("OCR_NOT_CONFIGURED");
                var outcome = ocr.extract(lease, prior == null ? null : prior.extraction());
                yield outcome == null
                        ? null
                        : new PreparedTaskSource(outcome.extraction().sources(), outcome.extraction());
            }
            default -> throw new TaskExecutionException("AGENT_ACTION_NOT_ALLOWED");
        };
    }

    private List<SourceDraft> prepare(JsonNode snapshot, boolean model) {
        String type = snapshot.path("type").asText("");
        if ("NOTE".equals(type)) {
            String raw = snapshot.path("noteText").asText("");
            String note = model ? raw : raw.trim();
            if (note.isBlank()) throw new TaskExecutionException("SOURCE_TEXT_NOT_AVAILABLE");
            if (model && note.length() > 60_000)
                throw new TaskExecutionException("MODEL_TEXT_LIMIT_EXCEEDED");
            int size = model ? 2000 : 500;
            List<SourceDraft> drafts = new ArrayList<>();
            for (int start = 0; start < note.length() && drafts.size() < (model ? 30 : 3); ) {
                int end = Math.min(start + size, note.length());
                if (end < note.length()
                        && Character.isHighSurrogate(note.charAt(end - 1))
                        && Character.isLowSurrogate(note.charAt(end))) end--;
                String part = note.substring(start, end);
                start = end;
                drafts.add(
                        new SourceDraft(
                                (model ? TaskCandidateService.MODEL_NOTE_CHUNK_BASE : 0)
                                        + drafts.size()
                                        + 1,
                                part,
                                "TEXT",
                                "文字笔记原文片段"));
            }
            if (model && drafts.stream().mapToInt(d -> d.content().length()).sum() != note.length())
                throw new TaskExecutionException("MODEL_TEXT_LIMIT_EXCEEDED");
            return drafts;
        }
        if ("IMAGE".equals(type)) {
            String title = snapshot.path("title").asText("").trim();
            if (title.isEmpty()) throw new TaskExecutionException("SOURCE_TEXT_NOT_AVAILABLE");
            return List.of(
                    new SourceDraft(
                            TaskCandidateService.MOCK_METADATA_CHUNK_NO,
                            title.substring(0, Math.min(title.length(), 500)),
                            "METADATA",
                            "仅引用资料标题；尚未提取文件正文"));
        }
        throw new TaskExecutionException("UNSUPPORTED_SOURCE_TYPE");
    }
}
