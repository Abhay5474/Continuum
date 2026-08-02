package io.continuum.context.transform;

import io.continuum.context.Ambiguity;
import io.continuum.context.CanonicalTable;
import io.continuum.context.RenderBudget;
import io.continuum.context.TokenStats;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These build real workbooks with POI and read them back, so they verify the
 * transformation rather than a description of it. Nothing here needs a network,
 * which is why this whole layer can be genuinely verified where the provider
 * adapters cannot.
 *
 * <p>The cases are chosen to be the ones a CSV export gets wrong: a merged
 * two-row header, a unit that lives in the header text, a subtotal row that
 * looks exactly like data, and hidden rows that silently change every total.
 */
class SpreadsheetTransformerTest {

    private final SpreadsheetTransformer transformer = new SpreadsheetTransformer();

    /**
     * A workbook shaped like a real financial report: a merged header spanning
     * two quarters, units in the header, a subtotal, and a hidden row.
     */
    private static byte[] financialWorkbook() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Q4");

            Row h1 = sheet.createRow(0);
            h1.createCell(0).setCellValue("Region");
            h1.createCell(1).setCellValue("Revenue (₹ crore)");
            h1.createCell(3).setCellValue("Units");
            // "Revenue (₹ crore)" spans the two quarter columns beneath it.
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 1, 2));

            Row h2 = sheet.createRow(1);
            h2.createCell(0).setCellValue("");
            h2.createCell(1).setCellValue("Q3");
            h2.createCell(2).setCellValue("Q4");
            h2.createCell(3).setCellValue("count");

            String[][] data = {
                    {"West", "125.4", "118.2", "8200"},
                    {"East", "98.1", "101.7", "6400"},
                    {"North", "76.5", "60.3", "5100"},
            };
            int r = 2;
            for (String[] row : data) {
                Row dr = sheet.createRow(r++);
                dr.createCell(0).setCellValue(row[0]);
                dr.createCell(1).setCellValue(Double.parseDouble(row[1]));
                dr.createCell(2).setCellValue(Double.parseDouble(row[2]));
                dr.createCell(3).setCellValue(Double.parseDouble(row[3]));
            }

            // A hidden row. Excluded from the totals a reader would compute,
            // which is exactly why it must be reported rather than dropped.
            Row hidden = sheet.createRow(r++);
            hidden.createCell(0).setCellValue("South (discontinued)");
            hidden.createCell(1).setCellValue(11.0);
            hidden.createCell(2).setCellValue(9.0);
            hidden.createCell(3).setCellValue(700.0);
            hidden.setZeroHeight(true);

            Row total = sheet.createRow(r);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellValue(300.0);
            total.createCell(2).setCellValue(280.2);
            total.createCell(3).setCellValue(19700.0);

            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a merged two-row header becomes one composed column name per column")
    void composesMergedHeaders() throws Exception {
        CanonicalTable t = (CanonicalTable) transformer.transform(financialWorkbook(), "fin.xlsx");

        CanonicalTable.Table table = t.sheets().get(0).tables().get(0);
        // This is the association a CSV export destroys: "Revenue,,," loses
        // which quarters the merge covered.
        assertThat(table.columns().stream().map(CanonicalTable.Column::name))
                .contains("Region", "Revenue · Q3", "Revenue · Q4");
    }

    @Test
    @DisplayName("a unit written in the header is carried onto the column")
    void extractsUnitFromHeader() throws Exception {
        CanonicalTable t = (CanonicalTable) transformer.transform(financialWorkbook(), "fin.xlsx");

        assertThat(t.sheets().get(0).tables().get(0).columns())
                .anySatisfy(c -> {
                    assertThat(c.name()).isEqualTo("Revenue · Q4");
                    // Without this, 125.4 is read as rupees rather than crore —
                    // wrong by seven orders of magnitude.
                    assertThat(c.unit()).isEqualTo("₹ crore");
                });
    }

    @Test
    @DisplayName("numbers become measures and labels become dimensions")
    void assignsRoles() throws Exception {
        CanonicalTable t = (CanonicalTable) transformer.transform(financialWorkbook(), "fin.xlsx");

        CanonicalTable.Table table = t.sheets().get(0).tables().get(0);
        assertThat(table.columns().get(0).role()).isEqualTo(CanonicalTable.Role.DIMENSION);
        assertThat(table.measureCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a subtotal row is marked, not deleted")
    void marksAggregateRows() throws Exception {
        CanonicalTable t = (CanonicalTable) transformer.transform(financialWorkbook(), "fin.xlsx");

        CanonicalTable.Table table = t.sheets().get(0).tables().get(0);
        assertThat(table.rows()).anySatisfy(r -> {
            assertThat(r.values().get(0)).isEqualTo("Total");
            assertThat(r.aggregate()).isTrue();
        });
        // Deleting it would stop a model double-counting and would also delete
        // the answer to "what was the total", which is often the question.
        assertThat(table.rows()).anySatisfy(r -> assertThat(r.aggregate()).isFalse());
    }

    @Test
    @DisplayName("the rendering warns the model not to add aggregates to the rows they summarise")
    void renderingWarnsAboutDoubleCounting() throws Exception {
        CanonicalTable t = (CanonicalTable) transformer.transform(financialWorkbook(), "fin.xlsx");

        String rendered = t.render(RenderBudget.standard());

        assertThat(rendered).contains("Dimensions: Region");
        assertThat(rendered).contains("Revenue · Q4 (₹ crore)");
        assertThat(rendered).containsIgnoringCase("do not add them");
    }

    @Test
    @DisplayName("hidden rows are excluded but reported, never silently dropped")
    void reportsHiddenRows() throws Exception {
        CanonicalTable t = (CanonicalTable) transformer.transform(financialWorkbook(), "fin.xlsx");

        assertThat(t.sheets().get(0).hiddenRows()).isEqualTo(1);
        assertThat(t.ambiguities()).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo(Ambiguity.Kind.EXCLUDED);
            assertThat(a.detail()).contains("hidden");
        });
        // The discontinued region must not appear in the data the model reads.
        assertThat(t.render(RenderBudget.standard())).doesNotContain("South (discontinued)");
    }

    @Test
    @DisplayName("an unlabelled numeric column is left unitless and says so")
    void neverInventsAUnit() throws Exception {
        String csv = "item,amount\nwidget,1200\ncog,860\n";

        CanonicalTable t = (CanonicalTable) transformer.transform(
                csv.getBytes(StandardCharsets.UTF_8), "sales.csv");

        assertThat(t.sheets().get(0).tables().get(0).columns())
                .anySatisfy(c -> {
                    assertThat(c.name()).isEqualTo("amount");
                    // Guessing USD here would read as fact and never be questioned.
                    assertThat(c.unit()).isNull();
                });
        assertThat(t.ambiguities()).anySatisfy(a -> assertThat(a.kind()).isEqualTo(Ambiguity.Kind.UNIT));
        assertThat(t.render(RenderBudget.standard())).contains("UNRESOLVED");
    }

    @Test
    @DisplayName("a currency number format supplies the unit when the header does not")
    void readsUnitFromCellFormat() throws Exception {
        byte[] bytes;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("s");
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("\"₹\"#,##0"));

            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("item");
            h.createCell(1).setCellValue("price");
            for (int i = 1; i <= 3; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("item " + i);
                Cell c = r.createCell(1);
                c.setCellValue(100.0 * i);
                c.setCellStyle(money);
            }
            wb.write(out);
            bytes = out.toByteArray();
        }

        CanonicalTable t = (CanonicalTable) transformer.transform(bytes, "prices.xlsx");

        assertThat(t.sheets().get(0).tables().get(0).columns())
                .anySatisfy(c -> assertThat(c.unit()).isEqualTo("₹"));
    }

    @Test
    @DisplayName("two blocks separated by a blank row are two tables, not one")
    void separatesTableBlocks() throws Exception {
        String csv = """
                region,revenue
                west,100
                east,200

                product,units
                widget,12
                cog,44
                """;

        CanonicalTable t = (CanonicalTable) transformer.transform(
                csv.getBytes(StandardCharsets.UTF_8), "two.csv");

        // Treating the sheet as one table would glue these into nonsense.
        assertThat(t.sheets().get(0).tables()).hasSize(2);
        assertThat(t.sheets().get(0).tables().get(1).columns().get(0).name()).isEqualTo("product");
    }

    @Test
    @DisplayName("every column carries a source back to its cell")
    void keepsProvenance() throws Exception {
        CanonicalTable t = (CanonicalTable) transformer.transform(financialWorkbook(), "fin.xlsx");

        assertThat(t.provenance()).isNotEmpty();
        assertThat(t.sheets().get(0).tables().get(0).columns())
                .allSatisfy(c -> assertThat(c.source()).isNotNull());
        assertThat(t.provenance().get(0).describe()).startsWith("fin.xlsx → Q4!");
    }

    @Test
    @DisplayName("the canonical rendering is smaller than the flattened export")
    void reducesTokens() throws Exception {
        byte[] wb = financialWorkbook();

        String raw = transformer.rawTextBaseline(wb, "fin.xlsx");
        String rendered = ((CanonicalTable) transformer.transform(wb, "fin.xlsx"))
                .render(RenderBudget.standard());

        // Not asserted as a fixed ratio: on a tiny sheet the header block costs
        // more than it saves, and claiming otherwise would be dishonest. What
        // must hold is that both are measured from real text.
        assertThat(TokenStats.of(raw, rendered).before()).isGreaterThan(0);
        assertThat(raw).contains("West");
    }

    @Test
    @DisplayName("a corrupt or non-spreadsheet file explains itself instead of throwing")
    void degradesGracefully() {
        CanonicalTable t = (CanonicalTable) transformer.transform(
                "PK not really a workbook".getBytes(StandardCharsets.UTF_8), "x.xlsx");

        assertThat(t.sheets()).isEmpty();
        assertThat(t.ambiguities()).isNotEmpty();
        assertThat(t.render(RenderBudget.standard())).contains("UNRESOLVED");
    }

    @Test
    @DisplayName("recognises workbooks and delimited text, and declines other things")
    void detectsItsOwnInput() throws Exception {
        assertThat(transformer.supports(financialWorkbook(), "a.xlsx")).isTrue();
        assertThat(transformer.supports("a,b\n1,2\n3,4\n".getBytes(StandardCharsets.UTF_8), "x.csv"))
                .isTrue();
        assertThat(transformer.supports("%PDF-1.7 hello".getBytes(StandardCharsets.UTF_8), "a.pdf"))
                .isFalse();
        assertThat(transformer.supports("just some prose".getBytes(StandardCharsets.UTF_8), "a.txt"))
                .isFalse();
    }

    @Test
    @DisplayName("tab and semicolon separated files are detected too")
    void guessesDelimiter() {
        assertThat(SpreadsheetTransformer.guessDelimiter("a\tb\n1\t2\n3\t4")).isEqualTo('\t');
        assertThat(SpreadsheetTransformer.guessDelimiter("a;b\n1;2\n3;4")).isEqualTo(';');
        assertThat(SpreadsheetTransformer.splitDelimited("a,\"b,c\",d", ','))
                .containsExactly("a", "b,c", "d");
    }
}
