package io.continuum.provenance;

import io.continuum.persistence.entity.DecisionRecordEntity;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.repository.DecisionRecordRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.persistence.repository.ProvenanceSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A caller only has the id printed on its answer ({@code req_42}); the trail is
 * filed under an internal id. Asking with the one you were given must work.
 */
class ProvenanceLookupTest {

    private final DecisionRecordRepository records = mock(DecisionRecordRepository.class);
    private final GatewayRequestLogRepository log = mock(GatewayRequestLogRepository.class);
    private final ProvenanceService service =
            new ProvenanceService(records, mock(ProvenanceSettingRepository.class), log);

    private GatewayRequestLogEntity logged(String developer, String trail) {
        return new GatewayRequestLogEntity(developer, "auto", "groq", "m", 0.1, "r", 10, 5, 0, true, 0)
                .withTraceId(trail);
    }

    @Test
    void theIdOnTheAnswerFindsItsTrail() {
        when(log.findById(42L)).thenReturn(Optional.of(logged("dev", "trail-uuid")));
        when(records.findByRequestIdAndDeveloperIdOrderBySeqAsc(anyString(), anyString())).thenReturn(List.of());
        when(records.findByRequestIdAndDeveloperIdOrderBySeqAsc("trail-uuid", "dev")).thenReturn(List.of(
                new DecisionRecordEntity("dev", "trail-uuid", 0, "ROUTE", "groq/m", "cheapest", null, 0, 0)));

        var graph = service.graph("dev", "req_42");

        assertThat(graph.get("found")).isEqualTo(true);
        assertThat(graph.get("requestId")).isEqualTo("req_42");
        assertThat(graph.get("traceId")).isEqualTo("trail-uuid");
        assertThat(service.graph("dev", "trail-uuid").get("found")).isEqualTo(true);
    }

    @Test
    void anotherAccountsRequestHasNoTrail() {
        when(log.findById(42L)).thenReturn(Optional.of(logged("someone-else", "trail-uuid")));
        when(records.findByRequestIdAndDeveloperIdOrderBySeqAsc(anyString(), anyString())).thenReturn(List.of());

        assertThat(service.graph("dev", "req_42").get("found")).isEqualTo(false);
    }
}
