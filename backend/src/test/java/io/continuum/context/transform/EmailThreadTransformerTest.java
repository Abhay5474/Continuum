package io.continuum.context.transform;

import io.continuum.context.CanonicalConversation;
import io.continuum.context.RenderBudget;
import io.continuum.context.TokenStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The email transformer is subtractive, which makes its failure mode deleting
 * something a person wrote. So these tests care as much about what survives as
 * about what goes.
 */
class EmailThreadTransformerTest {

    private final EmailThreadTransformer transformer = new EmailThreadTransformer();

    /** A four-deep top-posted thread with signatures and a legal disclaimer. */
    private static byte[] thread() {
        String eml = """
                From: Priya Nair <priya@acme.example>
                To: Support <support@vendor.example>
                Subject: Re: Invoice 4471 is wrong
                Date: Sun, 2 Aug 2026 14:05:00 +0000
                Message-ID: <4@acme.example>
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8

                That still does not match. Our PO says 12,500 and you have billed 15,200.
                Please reissue the invoice.

                --
                Priya Nair
                Head of Procurement, Acme Ltd
                +91 98765 43210
                priya@acme.example

                This email and any attachments are confidential and may be privileged. If you
                are not the intended recipient please delete it.

                On Sun, 2 Aug 2026 at 13:40, Support <support@vendor.example> wrote:
                > We have checked and the amount billed is 15,200 as agreed in the contract
                > dated March. Please see the attached statement.
                >
                > --
                > Vendor Support
                >
                > On Sun, 2 Aug 2026 at 12:10, Priya Nair <priya@acme.example> wrote:
                > > The invoice we received today shows a different total to our purchase
                > > order. Could you check it please?
                > >
                > > --
                > > Priya Nair
                > > Head of Procurement, Acme Ltd
                """;
        return eml.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the newest message keeps its own words")
    void keepsTheNewestMessage() {
        CanonicalConversation c =
                (CanonicalConversation) transformer.transform(thread(), "thread.eml");

        assertThat(c.messages()).isNotEmpty();
        assertThat(c.messages().get(0).body()).contains("Please reissue the invoice");
        assertThat(c.subject()).isEqualTo("Re: Invoice 4471 is wrong");
        assertThat(c.messages().get(0).from()).contains("Priya Nair");
    }

    @Test
    @DisplayName("the signature is removed at the RFC 3676 delimiter")
    void removesSignature() {
        CanonicalConversation c =
                (CanonicalConversation) transformer.transform(thread(), "thread.eml");

        String first = c.messages().get(0).body();
        assertThat(first).doesNotContain("+91 98765 43210");
        assertThat(first).doesNotContain("Head of Procurement");
        assertThat(c.signaturesRemoved()).isPositive();
    }

    @Test
    @DisplayName("the legal disclaimer goes, and it is longer than the message")
    void removesDisclaimer() {
        CanonicalConversation c =
                (CanonicalConversation) transformer.transform(thread(), "thread.eml");

        assertThat(c.messages().get(0).body()).doesNotContain("confidential and may be privileged");
    }

    @Test
    @DisplayName("quoted history is removed rather than repeated four times")
    void removesQuotedHistory() {
        CanonicalConversation c =
                (CanonicalConversation) transformer.transform(thread(), "thread.eml");

        // The oldest message appears three times in the raw source. Because a
        // model weights by repetition and position, leaving it in skews the
        // answer toward the beginning of a thread whose point is at the end.
        String rendered = c.render(RenderBudget.standard());
        int occurrences = rendered.split("purchase", -1).length - 1;
        assertThat(occurrences).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("nothing is reworded — every sentence is still the sender's")
    void neverRewrites() {
        CanonicalConversation c =
                (CanonicalConversation) transformer.transform(thread(), "thread.eml");

        // Exact substring, not a paraphrase. A transformer that summarised would
        // be non-deterministic and unauditable.
        assertThat(c.messages().get(0).body())
                .contains("Our PO says 12,500 and you have billed 15,200.");
    }

    @Test
    @DisplayName("the saving is real and is measured")
    void reducesTokens() {
        byte[] eml = thread();

        String raw = transformer.rawTextBaseline(eml, "thread.eml");
        String rendered = transformer.transform(eml, "thread.eml").render(RenderBudget.standard());

        TokenStats stats = TokenStats.of(raw, rendered);
        assertThat(stats.improved()).isTrue();
        assertThat(stats.reduction()).isGreaterThan(0.15);
    }

    @Test
    @DisplayName("attachments are named, not embedded")
    void namesAttachments() {
        String eml = """
                From: a@x.example
                To: b@y.example
                Subject: Statement
                Date: Sun, 2 Aug 2026 10:00:00 +0000
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="BOUND"

                --BOUND
                Content-Type: text/plain; charset=UTF-8

                Statement attached as agreed.
                --BOUND
                Content-Type: application/pdf; name="statement.pdf"
                Content-Disposition: attachment; filename="statement.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjQK
                --BOUND--
                """;

        CanonicalConversation c = (CanonicalConversation) transformer.transform(
                eml.getBytes(StandardCharsets.UTF_8), "a.eml");

        // The bytes are not the conversation. A model told an invoice was
        // attached can ask for it; a model sent the base64 just pays for it.
        assertThat(c.messages().get(0).attachments()).contains("statement.pdf");
        assertThat(c.messages().get(0).body()).contains("Statement attached");
        assertThat(c.render(RenderBudget.standard())).doesNotContain("JVBERi0xLjQK");
    }

    @Test
    @DisplayName("a message ending in prose keeps its ending")
    void doesNotCutRealText() {
        String eml = """
                From: a@x.example
                To: b@y.example
                Subject: Question
                Date: Sun, 2 Aug 2026 10:00:00 +0000
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8

                Could you confirm whether the delivery is still scheduled for Friday?
                We need to book the loading bay and cannot do that until we know.
                """;

        CanonicalConversation c = (CanonicalConversation) transformer.transform(
                eml.getBytes(StandardCharsets.UTF_8), "q.eml");

        // Deleting a person's last sentence is far worse than keeping a phone
        // number, so the signature heuristic must not fire here.
        assertThat(c.messages().get(0).body()).contains("cannot do that until we know");
    }

    @Test
    @DisplayName("HTML-only mail has its words recovered rather than its markup sent")
    void extractsHtmlText() {
        assertThat(EmailThreadTransformer.htmlToText(
                "<div><p>Hello <b>there</b></p><p>Second line</p></div>"))
                .contains("Hello there").contains("Second line").doesNotContain("<");
    }

    @Test
    @DisplayName("recognises email and declines things that are not")
    void detectsItsOwnInput() {
        assertThat(transformer.supports(thread(), "thread.eml")).isTrue();
        assertThat(transformer.supports(thread(), null)).isTrue();
        assertThat(transformer.supports(
                "a,b,c\n1,2,3\n4,5,6\n".getBytes(StandardCharsets.UTF_8), "x.csv")).isFalse();
    }

    @Test
    @DisplayName("something that is not MIME explains itself instead of throwing")
    void degradesGracefully() {
        CanonicalConversation c = (CanonicalConversation) transformer.transform(
                new byte[] {1, 2, 3}, "broken.eml");

        assertThat(c.messages()).isEmpty();
        assertThat(c.ambiguities()).isNotEmpty();
    }

    @Test
    @DisplayName("a trailing block of contact lines is a signature; two lines of prose are not")
    void signatureHeuristicIsConservative() {
        String signed = "Thanks for the update.\n\nRavi Kumar\nEngineering Manager\n"
                + "+91 90000 11111\nravi@x.example";
        String prose = "Thanks for the update.\n\nI will look at it tomorrow and come back to you.";

        assertThat(EmailThreadTransformer.heuristicSignatureStart(signed)).isPositive();
        assertThat(EmailThreadTransformer.heuristicSignatureStart(prose)).isEqualTo(-1);
    }
}
