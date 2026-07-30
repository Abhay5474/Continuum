package io.continuum.specialist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Hub is only worth having if searching it puts the right thing first and
 * if what it hands you actually works. These tests cover the ranking, and the
 * promise that no entry invents a third-party model id.
 */
class CuratedCatalogueTest {

    private final CuratedCatalogue catalogue = new CuratedCatalogue();

    @Test
    @DisplayName("A task word finds the entry for that task, first")
    void searchRanksTheObviousMatchFirst() {
        // Where a bring-your-own-key provider covers the task, it wins: it needs
        // only a key, whereas the "your own endpoint" entry asks the developer
        // to go and host a service before anything works.
        assertThat(catalogue.search("ocr", 10).get(0).id()).isEqualTo("ocrspace-ocr");
        // Deepgram and AssemblyAI are equally good answers to "transcription",
        // so which of the two comes first is an alphabetical tiebreak and not a
        // fact worth pinning. What matters is that the winner is one of them.
        assertThat(catalogue.search("transcription", 10).get(0).id())
                .isIn("deepgram-transcribe", "assemblyai-transcribe");
        // No BYOK moderation adapter exists yet, so this one is still the
        // self-hosted template.
        assertThat(catalogue.search("moderation", 10).get(0).id()).isEqualTo("http-moderation");
    }

    @Test
    @DisplayName("A usable entry outranks one that needs an endpoint you must host")
    void readyToUseEntriesRankAbove() {
        List<CatalogueEntry> found = catalogue.search("transcription", 10);

        int deepgram = indexOf(found, "deepgram-transcribe");
        int selfHosted = indexOf(found, "http-transcribe");

        assertThat(deepgram).isGreaterThanOrEqualTo(0);
        assertThat(selfHosted).isGreaterThan(deepgram);
    }

    private static int indexOf(List<CatalogueEntry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("Searching by provider finds that provider's entries")
    void searchByProvider() {
        List<CatalogueEntry> found = catalogue.search("roboflow", 10);

        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(e -> assertThat(e.provider()).isEqualTo("roboflow"));
    }

    @Test
    @DisplayName("Searching for a synonym in the tags still finds it")
    void tagsCarryTheSynonyms() {
        // Nobody types "structured extraction from documents". They type
        // "invoice".
        assertThat(catalogue.search("invoice", 10))
                .extracting(CatalogueEntry::id)
                .contains("http-extract");
        assertThat(catalogue.search("whisper", 10))
                .extracting(CatalogueEntry::id)
                .contains("http-transcribe");
    }

    @Test
    @DisplayName("An empty query browses the whole shelf")
    void emptyQueryReturnsEverything() {
        assertThat(catalogue.search("", 100)).hasSizeGreaterThanOrEqualTo(9);
        assertThat(catalogue.search(null, 100)).hasSizeGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("A query matching nothing returns nothing rather than everything")
    void unmatchedQueryIsEmpty() {
        // Falling back to "here is the whole list" would make a typo look like
        // a successful search.
        assertThat(catalogue.search("zzzzznotathing", 10)).isEmpty();
    }

    @Test
    @DisplayName("The limit is honoured")
    void limitIsRespected() {
        assertThat(catalogue.search("", 3)).hasSize(3);
    }

    // --- the promises ---------------------------------------------------------

    @Test
    @DisplayName("No entry invents a third-party model id")
    void noEntryFabricatesAModelId() {
        // The whole reason the catalogue is task-shaped. A hard-coded model id
        // that nobody probed produces a one-click add that 404s, which is worse
        // than asking the developer to paste an id they already have.
        for (CatalogueEntry e : catalogue.search("", 100)) {
            if ("roboflow".equals(e.provider())) {
                assertThat(e.modelPath())
                        .as("%s must not claim a specific Roboflow model exists", e.id())
                        .isEmpty();
                assertThat(e.needs()).contains("modelPath");
            }
        }
    }

    @Test
    @DisplayName("Every entry says what the developer still has to supply")
    void everyEntryDeclaresWhatItNeeds() {
        for (CatalogueEntry e : catalogue.search("", 100)) {
            assertThat(e.needs()).as("%s", e.id()).isNotEmpty();
            assertThat(e.note()).as("%s", e.id()).isNotBlank();
            assertThat(e.suggestedConfidence()).as("%s", e.id()).isBetween(0.0, 1.0);
            // A self-hosted entry cannot pre-fill a base URL it cannot know.
            if ("http".equals(e.provider())) {
                assertThat(e.needs()).as("%s", e.id()).contains("baseUrl");
            }
        }
    }

    @Test
    @DisplayName("Thresholds reflect the task, not one number everywhere")
    void thresholdsAreTaskSpecific() {
        // Moderation errs toward surfacing; OCR errs toward silence. If these
        // ever collapse to the same number the guidance has stopped being
        // guidance.
        double moderation = catalogue.byId("http-moderation").suggestedConfidence();
        double ocr = catalogue.byId("http-ocr").suggestedConfidence();

        assertThat(moderation).isLessThan(ocr);
    }

    @Test
    @DisplayName("A local catalogue is always available")
    void alwaysAvailable() {
        assertThat(catalogue.available()).isTrue();
        assertThat(catalogue.name()).isNotBlank();
    }

    @Test
    @DisplayName("byId finds entries and returns null for nonsense")
    void byIdLookup() {
        assertThat(catalogue.byId("http-ocr")).isNotNull();
        assertThat(catalogue.byId("nope")).isNull();
    }
}
