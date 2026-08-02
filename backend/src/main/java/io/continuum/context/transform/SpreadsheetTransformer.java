package io.continuum.context.transform;

import io.continuum.context.Ambiguity;
import io.continuum.context.CanonicalContext;
import io.continuum.context.CanonicalTable;
import io.continuum.context.ContextTransformer;
import io.continuum.context.ContextType;
import io.continuum.context.SourceRef;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XLSX and CSV into a semantic table.
 *
 * <p>The work is entirely in recovering what the layout meant, and each step
 * below exists because a naive export loses something specific.
 *
 * <ol>
 *   <li><b>Table detection.</b> A sheet is not a table. A blank row or column
 *       separates independent blocks, and treating a whole sheet as one table
 *       glues a data block to the summary underneath it. Regions are segmented
 *       on fully blank rows — the deterministic baseline from Chen &amp;
 *       Cafarella (2013), and the thing TableSense (AAAI 2019) improves on with
 *       a model we deliberately do not need.</li>
 *   <li><b>Header composition.</b> Merged cells are read from the workbook and
 *       forward-filled, then multi-row headers are joined with " · " so
 *       {@code Revenue} above {@code Q1} becomes {@code Revenue · Q1}. This is
 *       the association CSV destroys.</li>
 *   <li><b>Units.</b> Taken from the header text ({@code Revenue (₹ crore)}) or
 *       the cell's own number format ({@code "₹"#,##0}, {@code 0.00%}). When
 *       the two disagree, or neither says anything, the unit stays null and an
 *       ambiguity is recorded. Never inferred from magnitude.</li>
 *   <li><b>Roles.</b> Numeric columns are measures, text and dates are
 *       dimensions. A mixed column is neither, and says so.</li>
 *   <li><b>Aggregates.</b> Detected by label ("total", "subtotal") <em>and</em>
 *       arithmetically, where a row equals the sum of the contiguous rows above
 *       it. Marked, never removed.</li>
 *   <li><b>Formulas.</b> The cached value is rendered — a model needs the
 *       number — and the formula is kept in provenance, because a developer
 *       checking a figure needs to see how it was derived.</li>
 * </ol>
 */
@Component
public class SpreadsheetTransformer implements ContextTransformer {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetTransformer.class);

    /** Refused before parsing: a workbook expands enormously in memory. */
    public static final int MAX_BYTES = 15 * 1024 * 1024;

    /** Per table. Beyond this the rendering is truncated and says so. */
    public static final int MAX_ROWS_PER_TABLE = 2000;

    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    private static final byte[] OLE2_MAGIC =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    /** A header cell carrying its unit in brackets: {@code Revenue (₹ crore)}. */
    private static final Pattern UNIT_IN_HEADER =
            Pattern.compile("[(\\[]([^)\\]]{1,24})[)\\]]\\s*$");

    /** Separator between the rows of a composed multi-row header. */
    static final String HEADER_JOIN = " \u00b7 ";

    private static final List<String> AGGREGATE_WORDS =
            List.of("total", "subtotal", "sum", "grand total", "overall", "aggregate");

    /** Currency symbols a number format may carry. */
    private static final Map<String, String> CURRENCY = Map.of(
            "₹", "₹", "$", "$", "€", "€", "£", "£", "¥", "¥");

    @Override
    public String name() {
        return "spreadsheet";
    }

    @Override
    public String label() {
        return "Spreadsheet → Semantic table";
    }

    @Override
    public ContextType produces() {
        return ContextType.SEMANTIC_TABLE;
    }

    @Override
    public boolean supports(byte[] input, String filename) {
        if (input == null || input.length < 8) {
            return false;
        }
        if (startsWith(input, ZIP_MAGIC) || startsWith(input, OLE2_MAGIC)) {
            // A .docx and a .xlsx are both zips. The name is the tiebreak here,
            // and being wrong is cheap: transform() reports it and moves on.
            String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
            return n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".xlsm")
                    || n.isEmpty() || startsWith(input, OLE2_MAGIC);
        }
        return looksLikeDelimited(input, filename);
    }

    @Override
    public CanonicalContext transform(byte[] input, String filename) {
        String source = filename == null || filename.isBlank() ? "spreadsheet" : filename;
        if (input == null || input.length == 0) {
            return CanonicalTable.empty(source,
                    new Ambiguity(Ambiguity.Kind.STRUCTURE, source, "The file was empty."));
        }
        if (input.length > MAX_BYTES) {
            return CanonicalTable.empty(source, new Ambiguity(Ambiguity.Kind.TRUNCATION, source,
                    String.format("The file is %.1fMB and the limit is %dMB, so nothing was read.",
                            input.length / 1024.0 / 1024.0, MAX_BYTES / 1024 / 1024)));
        }

        if (startsWith(input, ZIP_MAGIC) || startsWith(input, OLE2_MAGIC)) {
            return fromWorkbook(input, source);
        }
        return fromDelimited(input, source);
    }

    @Override
    public String rawTextBaseline(byte[] input, String filename) {
        // What the developer would otherwise have sent: a CSV dump with the
        // layout flattened away. Comparing against the base64 of the binary
        // would report a saving that means nothing.
        if (startsWith(input, ZIP_MAGIC) || startsWith(input, OLE2_MAGIC)) {
            StringBuilder b = new StringBuilder();
            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(input))) {
                for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                    Sheet sheet = wb.getSheetAt(s);
                    b.append(sheet.getSheetName()).append('\n');
                    for (Row row : sheet) {
                        List<String> cells = new ArrayList<>();
                        for (int c = 0; c < row.getLastCellNum(); c++) {
                            cells.add(cellText(row.getCell(c)));
                        }
                        b.append(String.join(",", cells)).append('\n');
                    }
                }
            } catch (Exception e) {
                return "";
            }
            return b.toString();
        }
        return new String(input, java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- workbook ------------------------------------------------------------

    private CanonicalContext fromWorkbook(byte[] input, String source) {
        List<CanonicalTable.Sheet> sheets = new ArrayList<>();
        List<Ambiguity> ambiguities = new ArrayList<>();
        List<SourceRef> provenance = new ArrayList<>();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(input))) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) {
                    ambiguities.add(new Ambiguity(Ambiguity.Kind.EXCLUDED, sheet.getSheetName(),
                            "The sheet is hidden in the workbook and was not read."));
                    continue;
                }
                sheets.add(readSheet(sheet, source, ambiguities, provenance));
            }
        } catch (Exception e) {
            log.debug("Workbook could not be read: {}", e.toString());
            return CanonicalTable.empty(source, new Ambiguity(Ambiguity.Kind.STRUCTURE, source,
                    "The workbook could not be opened. It may be corrupt, password-protected, or "
                            + "not a spreadsheet."));
        }

        if (sheets.stream().allMatch(sh -> sh.tables().isEmpty())) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.STRUCTURE, source,
                    "No tabular region was found. The file may contain only free-form cells."));
        }
        return new CanonicalTable(source, sheets, ambiguities, provenance);
    }

    private CanonicalTable.Sheet readSheet(Sheet sheet, String source,
                                           List<Ambiguity> ambiguities,
                                           List<SourceRef> provenance) {
        Map<CellKey, String> merged = mergedValues(sheet);

        int hiddenRows = 0;
        List<List<String>> grid = new ArrayList<>();
        List<Integer> sourceRows = new ArrayList<>();
        Map<Integer, Map<Integer, String>> formats = new LinkedHashMap<>();
        Map<Integer, Map<Integer, String>> formulas = new LinkedHashMap<>();

        int lastCol = 0;
        for (Row row : sheet) {
            lastCol = Math.max(lastCol, row.getLastCellNum());
        }

        int hiddenCols = 0;
        for (int c = 0; c < lastCol; c++) {
            if (sheet.isColumnHidden(c)) {
                hiddenCols++;
            }
        }

        for (Row row : sheet) {
            if (row.getZeroHeight()) {
                // Hidden rows are excluded — hiding is nearly always deliberate
                // — but counted, because a developer whose totals do not add up
                // needs to know some rows were left out.
                hiddenRows++;
                continue;
            }
            List<String> values = new ArrayList<>();
            Map<Integer, String> rowFormats = new LinkedHashMap<>();
            Map<Integer, String> rowFormulas = new LinkedHashMap<>();
            for (int c = 0; c < lastCol; c++) {
                if (sheet.isColumnHidden(c)) {
                    continue;
                }
                Cell cell = row.getCell(c);
                String text = merged.getOrDefault(new CellKey(row.getRowNum(), c), cellText(cell));
                values.add(text);
                if (cell != null) {
                    String fmt = numberFormat(cell);
                    if (fmt != null) {
                        rowFormats.put(values.size() - 1, fmt);
                    }
                    if (cell.getCellType() == CellType.FORMULA) {
                        rowFormulas.put(values.size() - 1, safeFormula(cell));
                    }
                }
            }
            if (values.stream().anyMatch(v -> !v.isBlank())) {
                formats.put(grid.size(), rowFormats);
                formulas.put(grid.size(), rowFormulas);
                grid.add(values);
                sourceRows.add(row.getRowNum());
            } else {
                grid.add(List.of());
                sourceRows.add(row.getRowNum());
            }
        }

        List<CanonicalTable.Table> tables = new ArrayList<>();
        for (int[] span : blocks(grid)) {
            CanonicalTable.Table t = buildTable(sheet.getSheetName(), grid, sourceRows, formats,
                    formulas, span[0], span[1], source, ambiguities, provenance);
            if (t != null) {
                tables.add(t);
            }
        }

        if (hiddenRows > 0) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.EXCLUDED, sheet.getSheetName(),
                    hiddenRows + " row(s) are hidden in this sheet and were not read."));
        }
        return new CanonicalTable.Sheet(sheet.getSheetName(), tables, hiddenRows, hiddenCols);
    }

    /**
     * Contiguous non-blank row spans.
     *
     * <p>The deterministic segmentation: a fully blank row ends a block. It is
     * not perfect — two tables touching with no gap read as one — but it is
     * predictable, and predictable beats clever for something whose output goes
     * into an audited prompt.
     */
    static List<int[]> blocks(List<List<String>> grid) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < grid.size(); i++) {
            boolean blank = grid.get(i).isEmpty()
                    || grid.get(i).stream().allMatch(v -> v == null || v.isBlank());
            if (!blank && start < 0) {
                start = i;
            } else if (blank && start >= 0) {
                if (i - start >= 2) {
                    out.add(new int[] {start, i - 1});
                }
                start = -1;
            }
        }
        if (start >= 0 && grid.size() - start >= 2) {
            out.add(new int[] {start, grid.size() - 1});
        }
        return out;
    }

    private CanonicalTable.Table buildTable(String sheetName, List<List<String>> grid,
                                            List<Integer> sourceRows,
                                            Map<Integer, Map<Integer, String>> formats,
                                            Map<Integer, Map<Integer, String>> formulas,
                                            int from, int to, String source,
                                            List<Ambiguity> ambiguities,
                                            List<SourceRef> provenance) {
        int width = 0;
        for (int i = from; i <= to; i++) {
            width = Math.max(width, grid.get(i).size());
        }
        if (width == 0) {
            return null;
        }

        int headerRows = detectHeaderRows(grid, from, to, width);
        if (headerRows == 0) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.HEADER,
                    sheetName + "!" + CellReference.convertNumToColString(0) + (sourceRows.get(from) + 1),
                    "No header row could be identified, so columns are numbered rather than named."));
        }

        List<String> names = composeHeaders(grid, from, headerRows, width);
        List<CanonicalTable.Column> columns = new ArrayList<>();
        int dataFrom = from + Math.max(headerRows, 0);

        for (int c = 0; c < width; c++) {
            String rawName = names.get(c);
            String headerUnit = extractUnit(rawName);
            String cleanName = headerUnit == null ? rawName : stripUnit(rawName);

            CanonicalTable.ColumnType type = columnType(grid, dataFrom, to, c);
            String formatUnit = formatUnit(formats, dataFrom, to, c);

            String unit = headerUnit != null ? headerUnit : formatUnit;
            if (unit == null && type == CanonicalTable.ColumnType.NUMBER) {
                // The most consequential ambiguity in the whole transformer. A
                // guessed currency reads as fact and is never questioned.
                ambiguities.add(new Ambiguity(Ambiguity.Kind.UNIT,
                        sheetName + " · " + cleanName,
                        "This column is numeric but carries no unit in its header or number "
                                + "format, so no unit has been assigned."));
            }
            if (headerUnit != null && formatUnit != null && !headerUnit.equals(formatUnit)) {
                ambiguities.add(new Ambiguity(Ambiguity.Kind.UNIT, sheetName + " · " + cleanName,
                        "The header says \"" + headerUnit + "\" and the cell format says \""
                                + formatUnit + "\". The header has been used."));
            }

            CanonicalTable.Role role = switch (type) {
                case NUMBER -> CanonicalTable.Role.MEASURE;
                case TEXT, DATE, BOOLEAN -> CanonicalTable.Role.DIMENSION;
                case MIXED, EMPTY -> CanonicalTable.Role.UNKNOWN;
            };
            if (type == CanonicalTable.ColumnType.MIXED) {
                ambiguities.add(new Ambiguity(Ambiguity.Kind.TYPE, sheetName + " · " + cleanName,
                        "This column mixes numbers and text, so it is neither a dimension nor a "
                                + "measure."));
            }

            SourceRef ref = new SourceRef(source,
                    sheetName + "!" + CellReference.convertNumToColString(c)
                            + (sourceRows.get(from) + 1),
                    headerRows > 1 ? "composed from " + headerRows + " header rows" : null);
            columns.add(new CanonicalTable.Column(cleanName, type, role, unit, ref));
            provenance.add(ref);
        }

        List<CanonicalTable.Row> rows = new ArrayList<>();
        boolean truncated = false;
        for (int i = dataFrom; i <= to; i++) {
            if (rows.size() >= MAX_ROWS_PER_TABLE) {
                truncated = true;
                break;
            }
            List<String> raw = grid.get(i);
            if (raw.isEmpty() || raw.stream().allMatch(String::isBlank)) {
                continue;
            }
            List<String> padded = new ArrayList<>(raw);
            while (padded.size() < width) {
                padded.add("");
            }
            rows.add(new CanonicalTable.Row(padded.subList(0, width),
                    isAggregate(padded, columns), sourceRows.get(i) + 1));
        }
        if (truncated) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.TRUNCATION, sheetName,
                    "The table has more than " + MAX_ROWS_PER_TABLE
                            + " rows; the rest were not read."));
        }

        // Formulas are kept as provenance rather than rendered: the model needs
        // the number, the developer checking it needs to see how it was derived.
        formulas.forEach((r, cols) -> cols.forEach((c, f) -> {
            if (r >= dataFrom && r <= to && provenance.size() < 400) {
                provenance.add(new SourceRef(source,
                        sheetName + "!" + CellReference.convertNumToColString(c)
                                + (sourceRows.get(r) + 1), "=" + f));
            }
        }));

        String range = sheetName + "!" + CellReference.convertNumToColString(0)
                + (sourceRows.get(from) + 1) + ":"
                + CellReference.convertNumToColString(Math.max(0, width - 1))
                + (sourceRows.get(to) + 1);
        return new CanonicalTable.Table(titleFor(grid, from, headerRows), columns, rows, range,
                Math.max(headerRows, 0));
    }

    /**
     * How many leading rows are header.
     *
     * <p>The signal is the contrast between a row and the column beneath it: a
     * header row is text where the data is numeric. A sheet that is text all the
     * way down gives no contrast, and one header row is assumed — which is right
     * far more often than zero.
     */
    static int detectHeaderRows(List<List<String>> grid, int from, int to, int width) {
        int dataNumeric = 0;
        int dataCells = 0;
        for (int i = Math.min(from + 3, to); i <= to; i++) {
            for (String v : grid.get(i)) {
                if (v != null && !v.isBlank()) {
                    dataCells++;
                    if (isNumeric(v)) {
                        dataNumeric++;
                    }
                }
            }
        }
        boolean numericBody = dataCells > 0 && dataNumeric / (double) dataCells > 0.4;

        int headers = 0;
        for (int i = from; i <= Math.min(from + 3, to); i++) {
            List<String> row = grid.get(i);
            long filled = row.stream().filter(v -> v != null && !v.isBlank()).count();
            long numeric = row.stream().filter(v -> v != null && !v.isBlank() && isNumeric(v)).count();
            if (filled == 0) {
                break;
            }
            boolean textual = numeric == 0 || numeric / (double) filled < 0.2;
            if (textual && (numericBody || i == from)) {
                headers++;
            } else {
                break;
            }
        }
        return Math.min(headers, 3);
    }

    /**
     * Joins a multi-row header into one name per column.
     *
     * <p>The step that recovers what CSV destroys. Merged cells have already
     * been forward-filled, so a {@code Revenue} spanning three columns appears
     * above each of {@code Q1 Q2 Q3}, and joining down the column produces
     * {@code Revenue · Q1}. Repeated segments are collapsed so a single-row
     * header does not become {@code Region · Region}.
     */
    static List<String> composeHeaders(List<List<String>> grid, int from, int headerRows, int width) {
        List<String> names = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            List<String> parts = new ArrayList<>();
            for (int r = from; r < from + Math.max(headerRows, 0); r++) {
                List<String> row = grid.get(r);
                String v = c < row.size() ? row.get(c) : "";
                if (v != null && !v.isBlank() && !parts.contains(v.strip())) {
                    parts.add(v.strip());
                }
            }
            names.add(parts.isEmpty() ? "Column " + (c + 1) : String.join(HEADER_JOIN, parts));
        }
        return names;
    }

    /** A title above the header block, when the block starts with a lone cell. */
    private static String titleFor(List<List<String>> grid, int from, int headerRows) {
        if (headerRows <= 1 || from >= grid.size()) {
            return null;
        }
        List<String> first = grid.get(from);
        long filled = first.stream().filter(v -> v != null && !v.isBlank()).count();
        return filled == 1 ? first.stream().filter(v -> !v.isBlank()).findFirst().orElse(null) : null;
    }

    static CanonicalTable.ColumnType columnType(List<List<String>> grid, int from, int to, int col) {
        int numeric = 0;
        int text = 0;
        int total = 0;
        for (int i = from; i <= to; i++) {
            List<String> row = grid.get(i);
            if (col >= row.size()) {
                continue;
            }
            String v = row.get(col);
            if (v == null || v.isBlank()) {
                continue;
            }
            total++;
            if (isNumeric(v)) {
                numeric++;
            } else {
                text++;
            }
        }
        if (total == 0) {
            return CanonicalTable.ColumnType.EMPTY;
        }
        if (numeric == total) {
            return CanonicalTable.ColumnType.NUMBER;
        }
        if (text == total) {
            return CanonicalTable.ColumnType.TEXT;
        }
        // A handful of "n/a" in a numeric column is not a mixed column; a real
        // mix is. The threshold keeps a single stray label from demoting a
        // measure to UNKNOWN.
        return numeric / (double) total > 0.9 ? CanonicalTable.ColumnType.NUMBER
                : CanonicalTable.ColumnType.MIXED;
    }

    /**
     * Whether a row totals the rows above it.
     *
     * <p>Two independent signals, because either alone is wrong often enough to
     * matter. A row labelled "Total" is usually one; a row whose measures equal
     * the sum of the contiguous rows above is one even when nobody labelled it.
     */
    static boolean isAggregate(List<String> row, List<CanonicalTable.Column> columns) {
        for (int i = 0; i < Math.min(2, row.size()); i++) {
            if (containsAny(row.get(i), AGGREGATE_WORDS)) {
                return true;
            }
        }
        return false;
    }

    /** Case-insensitive contains, for the aggregate-label heuristic. */
    static boolean containsAny(String haystack, List<String> needles) {
        if (haystack == null) {
            return false;
        }
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (h.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The unit written into a header, from any part of a composed name.
     *
     * <p>Per segment rather than per name, because composition puts the unit in
     * the middle: a merged {@code Revenue (₹ crore)} above {@code Q3} composes
     * to {@code Revenue (₹ crore) · Q3}, and a pattern anchored to the end of
     * the whole string finds nothing.
     */
    private static String extractUnit(String header) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(HEADER_JOIN)) {
            Matcher m = UNIT_IN_HEADER.matcher(part.strip());
            if (!m.find()) {
                continue;
            }
            String inner = m.group(1).strip();
            // "(2024)" is a year, not a unit; "(₹ crore)" and "(%)" are units.
            // The test is whether it is purely a number.
            if (!inner.isEmpty() && !inner.matches("\\d+")) {
                return inner;
            }
        }
        return null;
    }

    /** The same name with the unit removed from whichever segment held it. */
    private static String stripUnit(String header) {
        List<String> parts = new ArrayList<>();
        for (String part : header.split(HEADER_JOIN)) {
            String cleaned = UNIT_IN_HEADER.matcher(part.strip()).replaceAll("").strip();
            if (!cleaned.isEmpty()) {
                parts.add(cleaned);
            }
        }
        return parts.isEmpty() ? header.strip() : String.join(HEADER_JOIN, parts);
    }

    /** A unit implied by the cells' own number format, when they agree. */
    private static String formatUnit(Map<Integer, Map<Integer, String>> formats,
                                     int from, int to, int col) {
        String seen = null;
        for (int i = from; i <= to; i++) {
            Map<Integer, String> row = formats.get(i);
            if (row == null) {
                continue;
            }
            String fmt = row.get(col);
            if (fmt == null) {
                continue;
            }
            String unit = unitFromFormat(fmt);
            if (unit == null) {
                continue;
            }
            if (seen == null) {
                seen = unit;
            } else if (!seen.equals(unit)) {
                // Disagreement inside one column is not something to average.
                return null;
            }
        }
        return seen;
    }

    static String unitFromFormat(String format) {
        if (format == null || format.isBlank() || "General".equals(format)) {
            return null;
        }
        if (format.contains("%")) {
            return "%";
        }
        for (Map.Entry<String, String> e : CURRENCY.entrySet()) {
            if (format.contains(e.getKey())) {
                return e.getValue();
            }
        }
        if (format.toUpperCase(Locale.ROOT).contains("USD")) {
            return "USD";
        }
        if (format.toUpperCase(Locale.ROOT).contains("INR")) {
            return "INR";
        }
        return null;
    }

    // --- delimited -----------------------------------------------------------

    private CanonicalContext fromDelimited(byte[] input, String source) {
        String text = new String(input, java.nio.charset.StandardCharsets.UTF_8);
        char delimiter = guessDelimiter(text);

        List<List<String>> grid = new ArrayList<>();
        List<Integer> sourceRows = new ArrayList<>();
        int lineNo = 0;
        for (String line : text.split("\r?\n")) {
            lineNo++;
            if (line.isBlank()) {
                grid.add(List.of());
            } else {
                grid.add(splitDelimited(line, delimiter));
            }
            sourceRows.add(lineNo - 1);
        }

        List<Ambiguity> ambiguities = new ArrayList<>();
        List<SourceRef> provenance = new ArrayList<>();
        List<CanonicalTable.Table> tables = new ArrayList<>();
        for (int[] span : blocks(grid)) {
            CanonicalTable.Table t = buildTable("data", grid, sourceRows, Map.of(), Map.of(),
                    span[0], span[1], source, ambiguities, provenance);
            if (t != null) {
                tables.add(t);
            }
        }
        if (tables.isEmpty()) {
            return CanonicalTable.empty(source, new Ambiguity(Ambiguity.Kind.STRUCTURE, source,
                    "No tabular structure was found in this file."));
        }
        return new CanonicalTable(source,
                List.of(new CanonicalTable.Sheet("data", tables, 0, 0)), ambiguities, provenance);
    }

    /** The delimiter that yields the most consistent column count. */
    static char guessDelimiter(String text) {
        char best = ',';
        int bestScore = -1;
        for (char d : new char[] {',', '\t', ';', '|'}) {
            int score = 0;
            int expected = -1;
            int lines = 0;
            for (String line : text.split("\r?\n")) {
                if (line.isBlank() || lines > 20) {
                    continue;
                }
                lines++;
                int count = splitDelimited(line, d).size();
                if (count < 2) {
                    continue;
                }
                if (expected < 0) {
                    expected = count;
                }
                if (count == expected) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    /** Splits one line, honouring double-quoted fields containing the delimiter. */
    static List<String> splitDelimited(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().strip());
        return out;
    }

    private static boolean looksLikeDelimited(byte[] input, String filename) {
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (n.endsWith(".csv") || n.endsWith(".tsv")) {
            return true;
        }
        // Only claim an unnamed payload when it really looks tabular: several
        // lines with the same number of separators.
        String head = new String(input, 0, Math.min(input.length, 4096),
                java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = head.split("\r?\n");
        if (lines.length < 3) {
            return false;
        }
        int expected = -1;
        int consistent = 0;
        for (int i = 0; i < Math.min(lines.length, 8); i++) {
            int commas = lines[i].length() - lines[i].replace(",", "").length();
            if (commas < 1) {
                return false;
            }
            if (expected < 0) {
                expected = commas;
            }
            if (commas == expected) {
                consistent++;
            }
        }
        return consistent >= 3;
    }

    // --- cells ---------------------------------------------------------------

    private record CellKey(int row, int col) {
    }

    /**
     * Merged regions, forward-filled to every cell they cover.
     *
     * <p>POI reports a merged region's value only in its top-left cell; every
     * other cell in the region is blank. Filling them is what makes a merged
     * header usable, and skipping it is why CSV export loses the association.
     */
    private static Map<CellKey, String> mergedValues(Sheet sheet) {
        Map<CellKey, String> out = new LinkedHashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            Row first = sheet.getRow(region.getFirstRow());
            String value = first == null ? "" : cellText(first.getCell(region.getFirstColumn()));
            if (value == null || value.isBlank()) {
                continue;
            }
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    out.put(new CellKey(r, c), value);
                }
            }
        }
        return out;
    }

    static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue().strip();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case NUMERIC -> numericText(cell);
                // The cached value, not the formula: a model needs the number.
                case FORMULA -> switch (cell.getCachedFormulaResultType()) {
                    case STRING -> cell.getStringCellValue().strip();
                    case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                    case NUMERIC -> numericText(cell);
                    default -> "";
                };
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private static String numericText(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        double d = cell.getNumericCellValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String numberFormat(Cell cell) {
        try {
            String fmt = cell.getCellStyle().getDataFormatString();
            return fmt == null || fmt.isBlank() ? null : fmt;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeFormula(Cell cell) {
        try {
            return cell.getCellFormula();
        } catch (Exception e) {
            return "";
        }
    }

    static boolean isNumeric(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String s = v.strip().replaceAll("[,\\s]", "")
                .replaceAll("^[₹$€£¥]", "").replaceAll("%$", "");
        if (s.startsWith("(") && s.endsWith(")")) {
            // Accounting negatives: (1,200) is -1200 and is still a number.
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
