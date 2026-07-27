package io.continuum.uncertainty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clustering is what makes the entropy semantic rather than lexical. The two
 * properties that matter: differently-worded agreement must collapse to one
 * cluster, and contradicting claims must never merge however similar the prose.
 */
class AnswerClustererTest {

    private final AnswerClusterer clusterer = new AnswerClusterer();

    @Test
    @DisplayName("the same fact phrased three ways is one meaning")
    void rewordingsCollapse() {
        var clusters = clusterer.cluster(List.of(
                "30 days.",
                "The refund window is 30 days from the renewal date.",
                "You have 30 days to request a refund."));

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).size()).isEqualTo(3);
    }

    @Test
    @DisplayName("number words and digits assert the same thing")
    void numberWordsNormalise() {
        assertThat(clusterer.sameMeaning("The window is thirty days.", "The window is 30 days.")).isTrue();
    }

    @Test
    @DisplayName("contradicting numbers never merge, however similar the sentence")
    void contradictionsSeparate() {
        // These two sentences are lexically almost identical. A cosine-only
        // comparator merges them and reports total confidence in a coin flip.
        assertThat(clusterer.sameMeaning(
                "The refund window is 30 days from the renewal date.",
                "The refund window is 14 days from the renewal date.")).isFalse();
    }

    @Test
    @DisplayName("three different answers are three meanings — maximal disagreement")
    void disagreementSplits() {
        var clusters = clusterer.cluster(List.of(
                "The company filed in 2019.",
                "The company filed in 2021.",
                "The company filed in 1998."));

        assertThat(clusters).hasSize(3);
    }

    @Test
    @DisplayName("a majority and a dissenter cluster 2:1, and the majority sorts first")
    void majorityFirst() {
        var clusters = clusterer.cluster(List.of(
                "The answer is 42.",
                "It is 42.",
                "The answer is 7."));

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(0).size()).isEqualTo(2);
        assertThat(clusters.get(1).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("prose with no claims falls back to lexical similarity")
    void proseFallsBackToSimilarity() {
        assertThat(clusterer.sameMeaning(
                "Photosynthesis converts light energy into chemical energy.",
                "Photosynthesis converts light into chemical energy in plants.")).isTrue();
        assertThat(clusterer.sameMeaning(
                "Photosynthesis converts light energy into chemical energy.",
                "The mitochondrion is the site of cellular respiration.")).isFalse();
    }

    @Test
    @DisplayName("a terse answer agrees with a verbose one — the cascade's old blind spot")
    void terseAgreesWithVerbose() {
        // Raw cosine puts these far apart because the vocabularies barely
        // overlap, which made the cascade's audit report false misses.
        assertThat(clusterer.sameMeaning(
                "30 days.",
                "Having reviewed the agreement, the refund window available to the customer "
                        + "extends to 30 days measured from the date of renewal, after which no "
                        + "further claim may be made under this clause.")).isTrue();
    }

    @Test
    @DisplayName("proper nouns count as claims")
    void properNounsAreClaims() {
        assertThat(clusterer.claims("The contract names Acme as the supplier.")).contains("acme");
        assertThat(clusterer.sameMeaning(
                "The supplier is Acme.", "The supplier is Globex.")).isFalse();
    }

    @Test
    @DisplayName("money and percentages normalise past their formatting")
    void moneyAndPercentages() {
        assertThat(clusterer.sameMeaning("The fee is $1,200.", "The fee is 1200 dollars.")).isTrue();
        assertThat(clusterer.sameMeaning("Growth was 12%.", "Growth was 21%.")).isFalse();
    }

    @Test
    @DisplayName("empty input yields no clusters")
    void emptyInput() {
        assertThat(clusterer.cluster(List.of())).isEmpty();
        assertThat(clusterer.cluster(null)).isEmpty();
    }
}
