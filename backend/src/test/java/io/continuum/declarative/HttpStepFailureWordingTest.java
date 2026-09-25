package io.continuum.declarative;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The words a failed step's timeline shows. They used to be "ConnectException". */
class HttpStepFailureWordingTest {

    @Test
    void namesTheHostAndWhatWentWrong() {
        assertThat(HttpStepActivity.describe(new java.net.ConnectException(), "http://10.0.0.9:9999/x", 30))
                .isEqualTo("could not connect to 10.0.0.9:9999 (nothing accepted the connection)");
        assertThat(HttpStepActivity.describe(new java.net.http.HttpTimeoutException("t"), "https://api.test/x", 12))
                .isEqualTo("no response from api.test within 12s");
        assertThat(HttpStepActivity.describe(new java.net.UnknownHostException("nope.test"), "https://nope.test", 5))
                .isEqualTo("host nope.test could not be found");
    }
}
