package io.continuum.context;

import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The context layer, on the chat path.
 *
 * <p>The pipeline endpoint has always transformed a recognised payload before
 * the model saw it. {@code /v1/chat/completions} did not, which meant the
 * feature only reached the endpoint fewer people call: paste a CSV into a chat
 * message and the model got the CSV. This closes that.
 *
 * <p><b>Opt-in, off by default.</b> A pipeline is a thing the developer
 * configured and can watch run. A chat completion is an API call whose prompt
 * they wrote themselves, and silently rewriting it would change answers they
 * are already depending on. While the toggle is off, no detection runs at all.
 *
 * <h2>What counts as data, and what is left alone</h2>
 *
 * <p>This is the whole difficulty. A chat message is prose with data in it, not
 * a file, and the cost of being wrong is asymmetric: failing to transform a
 * table wastes tokens, but mistaking a paragraph for a table destroys the
 * user's actual question. So only two shapes are considered, both of which
 * carry explicit evidence that the text is data:
 *
 * <ol>
 *   <li><b>The whole message</b>, when the entire body is recognised — someone
 *       pasting a log file as their message.</li>
 *   <li><b>Fenced blocks</b> — <code>```</code> is the user stating that what
 *       is inside is not prose. A language tag (<code>```csv</code>) is passed
 *       through as a filename hint, so the transformer's name-based detection
 *       applies.</li>
 * </ol>
 *
 * <p>A table floating loose in a paragraph is <em>not</em> picked up, and that
 * is deliberate rather than unfinished. The spreadsheet detector accepts three
 * lines that each contain a comma; ordinary prose does that all the time —
 * <em>"Hello, I have a question. / Yes, that is right. / No, it is not."</em>
 * is three lines with one comma each, and paragraph-scanning would rewrite it
 * into a two-column table. Guessing where data starts inside prose cannot be
 * done safely with content detectors this permissive, so it is not attempted.
 *
 * <p>A size floor applies on top: {@value #MIN_CHARS} characters and
 * {@value #MIN_LINES} lines. Below that there is no saving worth having, and
 * the false-positive risk is at its highest — a short message is exactly the
 * one a three-comma coincidence could ruin.
 *
 * <p>Only {@code user} messages are considered. A system message is the
 * developer's own instruction text; rewriting it would change behaviour they
 * set deliberately.
 */
@Service
public class PromptContextService {

    private static final Logger log = LoggerFactory.getLogger(PromptContextService.class);

    /** Below this there is nothing worth restructuring, and prose is likeliest. */
    static final int MIN_CHARS = 400;
    static final int MIN_LINES = 5;

    /** How much larger the canonical form may be. See {@link #withinBudget}. */
    static final double MAX_GROWTH = 2.5;

    /** The one transformer whose detector prose can satisfy by accident. */
    private static final String SPREADSHEET = "spreadsheet";

    /** ```lang\n ... ``` — the fence, with an optional language tag. */
    private static final Pattern FENCE = Pattern.compile(
            "```[ \\t]*([A-Za-z0-9_+-]*)[ \\t]*\\r?\\n(.*?)\\r?\\n?```", Pattern.DOTALL);

    /** Fence tags worth turning into a filename, because names beat sniffing. */
    private static final Map<String, String> TAG_HINTS = Map.of(
            "csv", "block.csv",
            "tsv", "block.tsv",
            "log", "block.log",
            "logs", "block.log",
            "eml", "block.eml",
            "email", "block.eml");

    private final ContextTransformService transforms;
    private final DeveloperAuthRepository devAuth;

    public PromptContextService(ContextTransformService transforms, DeveloperAuthRepository devAuth) {
        this.transforms = transforms;
        this.devAuth = devAuth;
    }

    public boolean enabledFor(String developerId) {
        return devAuth.findById(developerId)
                .map(DeveloperAuthEntity::isContextTransformEnabled)
                .orElse(false);
    }

    public void setEnabled(String developerId, boolean enabled) {
        DeveloperAuthEntity auth = devAuth.findById(developerId).orElse(null);
        if (auth == null) {
            return;
        }
        auth.setContextTransformEnabled(enabled);
        devAuth.save(auth);
    }

    /** What the console needs to say whether this is reaching models at all. */
    public Map<String, Object> status(String developerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gatewayEnabled", enabledFor(developerId));
        // Stated rather than implied: the pipeline path does not have a toggle
        // and never did, and a console that showed one switch would suggest
        // turning it off stops transformation everywhere. It does not.
        m.put("pipelineAlwaysOn", true);
        m.put("minChars", MIN_CHARS);
        m.put("minLines", MIN_LINES);
        return m;
    }

    /**
     * Rewrites recognised data inside a request's messages into canonical form.
     *
     * <p>Returns the request unchanged when the feature is off, when nothing is
     * recognised, or when anything at all goes wrong. This sits on the hot path
     * of every gateway call, so it must never be the reason a request fails.
     */
    public LlmRequest maybeTransform(String developerId, LlmRequest request) {
        if (request == null || request.messages() == null || !enabledFor(developerId)) {
            return request;
        }
        try {
            List<Message> out = new ArrayList<>(request.messages().size());
            boolean changed = false;
            for (Message msg : request.messages()) {
                String rewritten = rewrite(developerId, msg);
                if (rewritten == null) {
                    out.add(msg);
                } else {
                    changed = true;
                    out.add(new Message(msg.role(), rewritten, msg.toolCalls()));
                }
            }
            return changed
                    ? new LlmRequest(request.model(), out, request.maxTokens(), request.temperature())
                    : request;
        } catch (RuntimeException e) {
            log.warn("context transform skipped for {}: {}", developerId, e.toString());
            return request;
        }
    }

    /** The new content for a message, or null when it should be left alone. */
    private String rewrite(String developerId, Message msg) {
        if (msg == null || msg.content() == null || !isUser(msg.role())) {
            return null;
        }
        String content = msg.content();

        // 1. The whole message. Checked first: a message that is entirely a log
        //    file has no prose to preserve, and transforming it as one block
        //    keeps a single document's structure intact rather than splitting it
        //    at whatever line a fence happened to fall on.
        if (bigEnough(content)) {
            String canonical = canonicalise(developerId, content, null, "chat message");
            if (canonical != null) {
                return canonical;
            }
        }

        // 2. Fenced blocks. The fence is the user saying "this is not prose".
        Matcher m = FENCE.matcher(content);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        int last = 0;
        while (m.find()) {
            String tag = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
            String body = m.group(2);
            String canonical = bigEnough(body)
                    ? canonicalise(developerId, body, TAG_HINTS.get(tag), "chat message · fenced block")
                    : null;
            if (canonical == null) {
                continue;
            }
            sb.append(content, last, m.start()).append(canonical);
            last = m.end();
            any = true;
        }
        if (!any) {
            return null;
        }
        sb.append(content, last, content.length());
        return sb.toString();
    }

    /**
     * Transforms one block, or returns null when no transformer claims it.
     *
     * <p>Recorded only when the rewrite is actually used, so the console's
     * history shows real transforms rather than a row for every message the
     * gateway looked at and had no opinion about. The record holds counts and
     * shape, never the content.
     */
    private String canonicalise(String developerId, String block, String hint, String sourceName) {
        byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
        ContextTransformService.Result r =
                transforms.transform(bytes, hint == null ? sourceName : hint, RenderBudget.standard());
        if (!r.transformed()) {
            return null;
        }
        // The spreadsheet detector asks only that several lines carry the same
        // number of commas. That is the right bar for an uploaded file — a
        // thing named .csv should be believed — but a chat message has no name
        // and is usually prose, and English uses commas constantly. Four lines
        // of "Yes, that is right." satisfy the detector exactly, and this test
        // failed on precisely that input before the guard existed: the message
        // came back as a two-column table with the caller's question inside it.
        if (SPREADSHEET.equals(r.transformer()) && readsAsProse(block)) {
            return null;
        }
        if (!withinBudget(r.stats())) {
            return null;
        }
        transforms.record(developerId, r, hint == null ? sourceName : hint, bytes.length);
        return r.rendered();
    }

    /**
     * Whether a block reads as sentences rather than as data.
     *
     * <p>A veto on the delimited-text interpretation only, and applied only on
     * this path. Two independent signals, either of which is enough:
     *
     * <ul>
     *   <li><b>Terminal punctuation.</b> Rows of data do not end in full stops
     *       and question marks; sentences do.</li>
     *   <li><b>Words per field.</b> A cell holds a value — a name, a number, a
     *       date. A clause holds a thought. Averaging over three words to a
     *       field means the commas are grammar, not structure.</li>
     * </ul>
     *
     * <p>It costs a genuine case: a table whose last column is a written
     * comment will be left as the caller typed it. That is the right way round.
     * Failing to restructure a table wastes some tokens; restructuring a
     * paragraph mangles the question somebody asked.
     */
    private static boolean readsAsProse(String block) {
        String[] lines = block.split("\r?\n");
        int considered = 0;
        int sentences = 0;
        long words = 0;
        long fields = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (++considered > 40) {
                break;
            }
            String t = line.strip();
            char last = t.charAt(t.length() - 1);
            if (last == '.' || last == '?' || last == '!' || last == ':' || last == ';') {
                sentences++;
            }
            for (String cell : t.split(",", -1)) {
                fields++;
                String c = cell.strip();
                if (!c.isEmpty()) {
                    words += c.split("\\s+").length;
                }
            }
        }
        if (considered == 0) {
            return false;
        }
        double sentenceRate = sentences / (double) considered;
        double wordsPerField = fields == 0 ? 0 : words / (double) fields;
        return sentenceRate >= 0.4 || wordsPerField > 3.5;
    }

    /**
     * Whether the canonical form is worth what it costs.
     *
     * <p>Measured, because the intuition is wrong in both directions. A log
     * collapses — 850 tokens to 437 — but a table <em>grows</em>, 183 to 354,
     * and that is not a defect. The growth buys what a CSV cannot say: which
     * columns are dimensions and which are measures, that a row is an aggregate
     * and must not be added to the rows it summarises, and which units could not
     * be determined and have therefore not been guessed. Declining everything
     * that grew would have switched this off for spreadsheets entirely while
     * appearing to work.
     *
     * <p>Nor does growth run away with size. Rows measured at 24 / 200 / 600 /
     * 2000 give ratios of 1.93 / 1.33 / 1.27 / 0.47: the header block is fixed,
     * so it costs proportionally less as the table grows, and past a few
     * thousand rows the render budget truncates and the canonical form is
     * smaller outright. An absolute cap on added tokens was written here first
     * and then removed — it fired only in a narrow middle band, rejecting a
     * 1.27× transform while waving through a 1.93× one, which is incoherent.
     *
     * <p>What does run away is <b>width</b>. A thirty-column, six-row export —
     * an ordinary shape — goes 195 tokens to 1312, because every column earns a
     * line in the header and a numeric column with no unit earns an unresolved
     * note as well. Six rows of data cannot carry that. One ratio, sized to
     * admit the tall tables and refuse the wide ones.
     */
    private static boolean withinBudget(TokenStats stats) {
        int before = Math.max(1, stats.before());
        return stats.after() <= before * MAX_GROWTH;
    }

    private static boolean isUser(io.continuum.provider.model.Role role) {
        return role == io.continuum.provider.model.Role.USER;
    }

    private static boolean bigEnough(String s) {
        if (s == null || s.length() < MIN_CHARS) {
            return false;
        }
        int lines = 1;
        for (int i = 0; i < s.length() && lines < MIN_LINES; i++) {
            if (s.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines >= MIN_LINES;
    }
}
