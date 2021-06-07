package reciter.pubmed.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsmart.zerocode.core.domain.TargetEnv;
import org.jsmart.zerocode.core.runner.ZeroCodeUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@RunWith(ZeroCodeUnitRunner.class)
@TargetEnv("reciter_pubmed_loadtest_config.properties")
public class ReCiterPubmedApiLoadTest {
    private RestTemplate restTemplate = new RestTemplate();

    private final String RECITER_PUBMED_BASE_URL = "http://localhost:5000";

    @Test
    public void testReciterPubmedQuerycheck() {
        ResponseEntity<String> responseEntity = restTemplate.getForEntity(RECITER_PUBMED_BASE_URL + "/pubmed/query/300000", String.class);

        assertThat(responseEntity.getStatusCode().value()).isEqualTo(200);
    }

}
