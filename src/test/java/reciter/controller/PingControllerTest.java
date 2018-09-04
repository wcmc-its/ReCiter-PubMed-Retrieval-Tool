package reciter.controller;

import com.amazonaws.serverless.proxy.internal.testutils.AwsProxyRequestBuilder;
import com.amazonaws.serverless.proxy.internal.testutils.MockLambdaContext;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;
import org.testng.annotations.Test;
import reciter.Application;
import reciter.StreamLambdaHandler;

import static org.testng.Assert.assertEquals;

@ContextConfiguration(classes = {Application.class})
@WebAppConfiguration
public class PingControllerTest {
    private MockLambdaContext lambdaContext;
    private SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler;

    public PingControllerTest() {
        lambdaContext = new MockLambdaContext();
        this.handler = StreamLambdaHandler.handler;
    }

    @Test
    public void testPing() {
        AwsProxyRequest request = new AwsProxyRequestBuilder("/pubmed/ping", "GET").build();
        AwsProxyResponse response = handler.proxy(request, lambdaContext);

        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getBody(), "Healthy");
    }
}
