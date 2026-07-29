package io.continuum.specialist;

import io.continuum.tool.ToolKind;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * The shipped catalogue: integration templates, organised by the shape of the
 * task rather than by vendor.
 *
 * <p>What actually stops people wiring up a specialist is not finding a model —
 * it is the four questions afterwards. Which auth style does this provider use?
 * What does its response look like? What counts as a confident detection for
 * <em>this kind</em> of task? What do I have to supply myself? Every entry here
 * answers those, and the console fills the form in from it.
 *
 * <p><b>No entry names a specific third-party model.</b> That is deliberate.
 * Hard-coding model ids nobody has probed would produce a catalogue whose
 * one-click add 404s, which is worse than making the developer paste an id they
 * already have. Where the id is yours, the entry says so and asks for it.
 *
 * <p>The thresholds are the part worth reading. They are not one number applied
 * everywhere: a moderation check that misses things is useless and a medical
 * check that guesses is dangerous, so they point in opposite directions.
 */
@Component
public class CuratedCatalogue implements CatalogueSource {

    private static final String SOURCE = "Continuum";

    private static final List<CatalogueEntry> ENTRIES = List.of(
            new CatalogueEntry(
                    "roboflow-detect",
                    "Object or defect detection (Roboflow)",
                    "A model you trained in Roboflow, returning labelled boxes with confidences. "
                            + "The common case: damage, defects, wear, presence or absence of a part.",
                    "roboflow", "https://detect.roboflow.com", "", "image", ToolKind.DETECTION,
                    0.40,
                    List.of("roboflow", "detection", "object", "defect", "damage", "boxes", "vision"),
                    List.of("modelPath", "secret"),
                    "Paste the model id from your Roboflow workspace — it looks like "
                            + "project-name/3. Continuum pushes the confidence threshold down to "
                            + "Roboflow so weak detections are filtered before they cross the wire.",
                    SOURCE),

            new CatalogueEntry(
                    "roboflow-classify",
                    "Image classification (Roboflow)",
                    "A Roboflow classifier returning one or more labels for the whole image "
                            + "rather than regions within it.",
                    "roboflow", "https://classify.roboflow.com", "", "image", ToolKind.CLASSIFICATION,
                    0.50,
                    List.of("roboflow", "classification", "classify", "label", "category", "vision"),
                    List.of("modelPath", "secret"),
                    "Higher default threshold than detection: a classifier assigns the whole "
                            + "image, so a wrong answer is wrong about everything rather than "
                            + "about one region.",
                    SOURCE),

            new CatalogueEntry(
                    "http-detect",
                    "Object detection (your own endpoint)",
                    "Your own model behind HTTP — FastAPI, SageMaker, TorchServe, an internal "
                            + "service. Returns predictions with labels and scores.",
                    "http", "", "/predict", "image", ToolKind.DETECTION,
                    0.40,
                    List.of("custom", "http", "detection", "self-hosted", "fastapi", "sagemaker",
                            "torchserve", "vision"),
                    List.of("baseUrl", "modelPath", "secret"),
                    "The adapter recognises prediction lists, single label/score pairs and bare "
                            + "score maps, so most shapes work without changes. Probe it and the "
                            + "console shows what came back beside what Continuum understood.",
                    SOURCE),

            new CatalogueEntry(
                    "http-classify",
                    "Text classification (your own endpoint)",
                    "Sentiment, intent, topic or routing labels for a piece of text, before the "
                            + "language model sees it.",
                    "http", "", "/classify", "text", ToolKind.CLASSIFICATION,
                    0.50,
                    List.of("custom", "http", "text", "classification", "sentiment", "intent",
                            "topic", "routing", "nlp"),
                    List.of("baseUrl", "modelPath", "secret"),
                    "Useful ahead of a general model when the routing decision should be cheap "
                            + "and consistent rather than re-reasoned every request.",
                    SOURCE),

            new CatalogueEntry(
                    "http-ocr",
                    "OCR / text extraction (your own endpoint)",
                    "Pull text out of an image or scan so the language model reasons over words "
                            + "instead of pixels it cannot see.",
                    "http", "", "/ocr", "image", ToolKind.OCR,
                    0.60,
                    List.of("ocr", "text", "extraction", "document", "scan", "receipt", "invoice",
                            "http", "custom"),
                    List.of("baseUrl", "modelPath", "secret"),
                    "Threshold set high on purpose. OCR confidences are usually decisive, and a "
                            + "half-read word passed on as a finding becomes a confidently wrong "
                            + "quotation.",
                    SOURCE),

            new CatalogueEntry(
                    "http-transcribe",
                    "Audio transcription (your own endpoint)",
                    "Turn speech into text before summarising, routing or answering — a call "
                            + "recording, a voice note, a meeting.",
                    "http", "", "/transcribe", "audio", ToolKind.TRANSCRIPTION,
                    0.50,
                    List.of("audio", "speech", "transcription", "whisper", "voice", "meeting",
                            "http", "custom"),
                    List.of("baseUrl", "modelPath", "secret"),
                    "Returns segments as findings. The context builder states the confidence per "
                            + "segment, so the model can hedge on the parts that were unclear "
                            + "rather than on the whole transcript.",
                    SOURCE),

            new CatalogueEntry(
                    "http-extract",
                    "Structured extraction from documents (your own endpoint)",
                    "Pull named fields out of a document or payload — totals, dates, parties, "
                            + "reference numbers — and hand them over as facts.",
                    "http", "", "/extract", "json", ToolKind.EXTRACTION,
                    0.55,
                    List.of("extraction", "document", "fields", "invoice", "form", "structured",
                            "json", "http", "custom"),
                    List.of("baseUrl", "modelPath", "secret"),
                    "Extracted fields reach the model as stated facts with confidences rather "
                            + "than as a blob to re-read, which is what stops it inventing a "
                            + "total that was never on the page.",
                    SOURCE),

            new CatalogueEntry(
                    "http-moderation",
                    "Content safety / moderation (your own endpoint)",
                    "Flag unsafe or unwanted content before it reaches the model or the user.",
                    "http", "", "/moderate", "text", ToolKind.MODERATION,
                    0.25,
                    List.of("moderation", "safety", "abuse", "nsfw", "toxicity", "filter",
                            "http", "custom"),
                    List.of("baseUrl", "modelPath", "secret"),
                    "Low threshold, deliberately the opposite of the others. For a safety check "
                            + "a missed flag costs more than a spurious one, so it errs toward "
                            + "surfacing rather than filtering.",
                    SOURCE),

            new CatalogueEntry(
                    "http-generic",
                    "Anything else over HTTP",
                    "A blank template for a JSON endpoint that does not match the shapes above.",
                    "http", "", "/", "json", ToolKind.CUSTOM,
                    0.40,
                    List.of("custom", "http", "generic", "blank", "other", "json"),
                    List.of("baseUrl", "modelPath", "secret"),
                    "Same forgiving parser as the rest. If the probe comes back UNPARSEABLE the "
                            + "endpoint answered and only the response shape needs attention.",
                    SOURCE));

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public boolean available() {
        // Held in the binary, so it cannot be down.
        return true;
    }

    @Override
    public List<CatalogueEntry> search(String query, int limit) {
        return ENTRIES.stream()
                .filter(e -> e.score(query) > 0)
                .sorted(Comparator.comparingInt((CatalogueEntry e) -> e.score(query)).reversed()
                        .thenComparing(CatalogueEntry::title))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Exact lookup for the install endpoint. */
    public CatalogueEntry byId(String id) {
        return ENTRIES.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }
}
