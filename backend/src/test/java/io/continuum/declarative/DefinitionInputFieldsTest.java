package io.continuum.declarative;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionInputFieldsTest {

    @Test
    void listsEachInputFieldOnceInFirstUseOrder() {
        String spec = "{\"steps\":[{\"id\":\"verify\",\"call\":{\"url\":\"http://x/v?orderId=${input.orderId}\"}},"
                + "{\"id\":\"refund\",\"call\":{\"body\":{\"o\":\"${input.orderId}\",\"r\":\"${input.reason}\"}}},"
                + "{\"id\":\"pickup\",\"condition\":\"${input.collectItem} == true\"}]}";
        assertThat(DefinitionService.inputFields(spec)).isEqualTo(List.of("orderId", "reason", "collectItem"));
    }

    @Test
    void noInputReferencesMeansNoFields() {
        assertThat(DefinitionService.inputFields("{\"steps\":[{\"id\":\"a\",\"url\":\"${steps.b.x}\"}]}")).isEmpty();
        assertThat(DefinitionService.inputFields(null)).isEmpty();
    }
}
