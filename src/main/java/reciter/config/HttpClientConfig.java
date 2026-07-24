package reciter.config;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a single shared, pooled, timeout-bounded {@link CloseableHttpClient} for all
 * outbound calls to the NCBI E-utilities API.
 *
 * <p>Using one pooled client (instead of {@code HttpClients.createDefault()} per request)
 * allows TCP/TLS connection reuse and prevents connection/file-descriptor leaks under load.
 * The {@link RequestConfig} timeouts ensure a slow or stalled NCBI response can never wedge
 * a servlet worker thread indefinitely.
 */
@Configuration
public class HttpClientConfig {

    /** Time to establish a TCP connection to NCBI. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** Time to wait between data packets once connected (read timeout). */
    private static final int SOCKET_TIMEOUT_MILLIS = 60_000;

    /** Time to wait for a connection from the pool. */
    private static final int CONNECTION_REQUEST_TIMEOUT_MILLIS = 5_000;

    private static final int MAX_TOTAL_CONNECTIONS = 50;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 50;

    @Bean(destroyMethod = "close")
    public CloseableHttpClient pubMedHttpClient() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(MAX_TOTAL_CONNECTIONS);
        connectionManager.setDefaultMaxPerRoute(MAX_CONNECTIONS_PER_ROUTE);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSocketTimeout(SOCKET_TIMEOUT_MILLIS)
                .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MILLIS)
                .setCookieSpec(CookieSpecs.STANDARD)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }
}
