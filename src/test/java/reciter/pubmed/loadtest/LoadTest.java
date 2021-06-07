package reciter.pubmed.loadtest;

import org.jsmart.zerocode.core.domain.LoadWith;
import org.jsmart.zerocode.core.domain.TestMapping;
import org.jsmart.zerocode.core.runner.parallel.ZeroCodeLoadRunner;
import org.junit.runner.RunWith;

@TestMapping(testClass = ReCiterPubmedApiLoadTest.class, testMethod = "testReciterPubmedQuerycheck")
@LoadWith("reciter_pubmed_loadtest_config.properties")
@RunWith(ZeroCodeLoadRunner.class)
public class LoadTest {
    
}
