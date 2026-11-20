# Context transformer samples

Four files to upload on the console's **Context transformers** page (*Prompt → Context →
Transformers → Upload a file*). Each one is recognised from its contents and turned into
the context an AI model should actually receive.

| File | Recognised as | What to point at | Measured |
|---|---|---|---|
| `corporate-order-thread.eml` | Email thread → Conversation | A 5-message corporate order (200 Diwali hampers). Every reply quotes the whole thread again, with signatures and legal disclaimers. The output keeps each message once, in order, with only what its sender wrote. | 1,403 → 685 tokens (51% smaller) |
| `checkout-incident.log` | Logs → Incident context | 958 lines from a checkout outage: a deploy, 46 identical UPI timeout stack traces, 22 database-pool errors, a rollback, recovery. The output gives the time window, severity counts, each distinct exception once with how many times it happened, and the timeline in order. | 21,418 → 5,268 tokens (75% smaller) |
| `regional-sales-q3.xlsx` | Spreadsheet → Semantic table | Merged two-row headers (*Revenue (₹ lakh)* over *Jul / Aug / Sep*), currency and percent formats, region subtotals and a grand total, and a second table below a blank gap. The output names every column (*Revenue · Aug (₹ lakh)*), shows return rates as percentages, splits the two tables, and marks the 4 total rows so a model does not add them twice. | Grows, on purpose (see below) |
| `regional-sales-q3.csv` | Spreadsheet → Semantic table | The same sheet exported as CSV. Compare with the `.xlsx`: a CSV has no merged cells, so *Aug* and *Sep* can no longer be tied to *Revenue*, and the percent format is gone. | |

## A 90-second demo

1. Upload `checkout-incident.log`. Scroll the original: it is a wall of repeated stack
   traces. Then show the result: one line per distinct exception with its count, the
   deploy and the rollback on the timeline, and **75% fewer tokens**.
2. Upload `corporate-order-thread.eml`. The original repeats itself five times over;
   the result is five clean messages, and nothing a person wrote was reworded.
3. Upload `regional-sales-q3.csv`, then `regional-sales-q3.xlsx`. In the CSV result the
   months after *Jul* lose their *Revenue* label; in the workbook result every column is
   named and the totals are marked as totals.

## Why the spreadsheet does not get smaller

Continuum measures the tokens before and after instead of assuming structure is
cheaper, and reports growth when there is growth. For a table the gain is
correctness: a model reading the raw cells cannot tell which *Aug* is revenue and
which is orders, and will happily add a subtotal to the rows it already sums. The
few extra tokens are the column names, units and the "these rows are totals" note
that prevent those mistakes.
