package io.continuum.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A spreadsheet with its meaning recovered from its layout.
 *
 * <p>A spreadsheet is a picture of data, not data. The association between a
 * merged {@code Revenue} header and the three quarter columns beneath it exists
 * only because a human can see the merge; export to CSV and it becomes
 * {@code Revenue,,,} — the relationship is destroyed, not merely obscured, and
 * no prompting recovers it. Subtotal rows look exactly like data rows and get
 * double-counted in any aggregate a model attempts. A column headed
 * {@code Revenue (₹ crore)} carries its unit in prose that CSV keeps but nothing
 * interprets, so 125.4 is read as rupees and the answer is wrong by seven orders
 * of magnitude.
 *
 * <p>This model holds what was recovered: composed headers, per-column type,
 * unit and role, which rows are aggregates, and where every column came from.
 *
 * <p><b>Subtotals are marked, never deleted.</b> Removing them would be the
 * easy way to stop a model double-counting, and it would also delete the answer
 * to "what was the regional total" — which is frequently the question. Marking
 * lets the model use them correctly.
 */
public record CanonicalTable(String sourceName, List<Sheet> sheets,
                             List<Ambiguity> ambiguities,
                             List<SourceRef> provenance) implements CanonicalContext {

    /** What a column is for. Drives both rendering and what a model may do. */
    public enum Role {
        /** Something you group or filter by: region, month, product. */
        DIMENSION,
        /** Something you aggregate: revenue, units, headcount. */
        MEASURE,
        /** Could not be established. Deliberately not defaulted to either. */
        UNKNOWN
    }

    public enum ColumnType { NUMBER, TEXT, DATE, BOOLEAN, MIXED, EMPTY }

    /**
     * One column, after the header block has been resolved.
     *
     * @param name  the composed header — {@code Revenue · Q1} for a merged
     *              two-row header, so the association survives flattening
     * @param unit  the unit, or null when it could not be determined. Never
     *              guessed: an unlabelled number column stays unitless and an
     *              {@link Ambiguity.Kind#UNIT} is recorded
     */
    public record Column(String name, ColumnType type, Role role, String unit,
                         SourceRef source) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type.name());
            m.put("role", role.name());
            m.put("unit", unit);
            m.put("source", source == null ? null : source.toMap());
            return m;
        }

        /** {@code Revenue (₹ crore)}, or just the name when there is no unit. */
        public String display() {
            return unit == null || unit.isBlank() ? name : name + " (" + unit + ")";
        }
    }

    /**
     * One row of values.
     *
     * @param aggregate whether this row totals others rather than standing
     *                  alone. Kept, because "which region declined most" needs
     *                  the detail rows and "what was the total" needs this one
     */
    public record Row(List<String> values, boolean aggregate, int sourceRow) {
    }

    /**
     * A rectangular region that behaves as one table.
     *
     * <p>A sheet often holds several: a data block, a summary block below it,
     * and a legend. Treating the sheet as one table merges them into nonsense,
     * so they are found separately.
     */
    public record Table(String title, List<Column> columns, List<Row> rows,
                        String range, int headerRows) {

        public int measureCount() {
            return (int) columns.stream().filter(c -> c.role() == Role.MEASURE).count();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("range", range);
            m.put("headerRows", headerRows);
            m.put("columns", columns.stream().map(Column::toMap).toList());
            m.put("rowCount", rows.size());
            m.put("aggregateRows", rows.stream().filter(Row::aggregate).count());
            return m;
        }
    }

    /**
     * @param hiddenRows rows hidden in the source and excluded. Reported rather
     *                   than silently dropped — hiding is usually deliberate,
     *                   but a developer whose numbers do not add up deserves to
     *                   know why
     */
    public record Sheet(String name, List<Table> tables, int hiddenRows, int hiddenColumns) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("hiddenRows", hiddenRows);
            m.put("hiddenColumns", hiddenColumns);
            m.put("tables", tables.stream().map(Table::toMap).toList());
            return m;
        }
    }

    @Override
    public ContextType type() {
        return ContextType.SEMANTIC_TABLE;
    }

    @Override
    public Map<String, Object> structure() {
        int tables = 0;
        int columns = 0;
        int rows = 0;
        int units = 0;
        int measures = 0;
        for (Sheet s : sheets) {
            tables += s.tables().size();
            for (Table t : s.tables()) {
                columns += t.columns().size();
                rows += t.rows().size();
                measures += t.measureCount();
                units += (int) t.columns().stream()
                        .filter(c -> c.unit() != null && !c.unit().isBlank()).count();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sheets", sheets.size());
        m.put("tables", tables);
        m.put("columns", columns);
        m.put("rows", rows);
        m.put("measures", measures);
        m.put("unitsDetected", units);
        return m;
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type().name());
        m.put("sourceName", sourceName);
        m.put("sheets", sheets.stream().map(Sheet::toMap).toList());
        m.put("structure", structure());
        m.put("ambiguities", ambiguities.stream().map(Ambiguity::toMap).toList());
        return m;
    }

    /**
     * The rendering a model actually reads.
     *
     * <p>A header block first — dimensions, measures with their units, what was
     * excluded — then the rows. The header block is what lets a model answer
     * "which region had the largest decline" without inferring the schema from
     * the data, which is the thing it is worst at.
     */
    @Override
    public String render(RenderBudget budget) {
        StringBuilder b = new StringBuilder();
        int limit = budget.maxChars();
        boolean truncated = false;

        b.append("SPREADSHEET: ").append(sourceName).append('\n');
        b.append(sheets.size()).append(sheets.size() == 1 ? " sheet" : " sheets").append(".\n\n");

        for (Sheet sheet : sheets) {
            for (Table t : sheet.tables()) {
                if (b.length() > limit) {
                    truncated = true;
                    break;
                }
                b.append("TABLE");
                if (t.title() != null && !t.title().isBlank()) {
                    b.append(": ").append(t.title());
                }
                b.append("  [sheet \"").append(sheet.name()).append("\", range ")
                        .append(t.range()).append("]\n");

                String dims = t.columns().stream().filter(c -> c.role() == Role.DIMENSION)
                        .map(Column::name).reduce((x, y) -> x + ", " + y).orElse("");
                String measures = t.columns().stream().filter(c -> c.role() == Role.MEASURE)
                        .map(Column::display).reduce((x, y) -> x + ", " + y).orElse("");
                if (!dims.isBlank()) {
                    b.append("Dimensions: ").append(dims).append('\n');
                }
                if (!measures.isBlank()) {
                    b.append("Measures: ").append(measures).append('\n');
                }

                long aggregates = t.rows().stream().filter(Row::aggregate).count();
                if (aggregates > 0) {
                    // Said explicitly: a model that adds these to the detail
                    // rows double-counts, and it has no way to tell otherwise.
                    b.append("Note: ").append(aggregates)
                            .append(aggregates == 1 ? " row is an aggregate" : " rows are aggregates")
                            .append(" of other rows in this table — do not add them to the rows they summarise.\n");
                }
                if (sheet.hiddenRows() > 0 || sheet.hiddenColumns() > 0) {
                    b.append("Note: ").append(sheet.hiddenRows()).append(" hidden row(s) and ")
                            .append(sheet.hiddenColumns())
                            .append(" hidden column(s) on this sheet were excluded.\n");
                }
                b.append('\n');

                b.append(t.columns().stream().map(Column::display)
                        .reduce((x, y) -> x + " | " + y).orElse("")).append('\n');

                for (Row r : t.rows()) {
                    if (b.length() > limit) {
                        truncated = true;
                        break;
                    }
                    b.append(String.join(" | ", r.values()));
                    if (r.aggregate()) {
                        b.append("   ← aggregate");
                    }
                    b.append('\n');
                }
                b.append('\n');
            }
        }

        if (truncated) {
            b.append("[Rendering stopped at the configured budget. Not all rows are shown — do "
                    + "not assume the rows above are the whole table.]\n");
        }

        if (!ambiguities.isEmpty()) {
            // Carried into the prompt, not just the API. A model that is told a
            // unit is unknown will say so; one that is not will pick one.
            b.append("\nUNRESOLVED — the following could not be determined from the file, and "
                    + "have not been guessed:\n");
            for (Ambiguity a : ambiguities) {
                b.append("  - ").append(a.where()).append(": ").append(a.detail()).append('\n');
            }
        }

        if (budget.includeProvenance() && !provenance.isEmpty()) {
            b.append("\nSOURCES\n");
            for (SourceRef r : provenance) {
                b.append("  - ").append(r.describe()).append('\n');
            }
        }
        return b.toString();
    }

    /** Convenience for building an empty result that still explains itself. */
    public static CanonicalTable empty(String sourceName, Ambiguity why) {
        List<Ambiguity> a = new ArrayList<>();
        a.add(why);
        return new CanonicalTable(sourceName, List.of(), a, List.of());
    }
}
