package reciter.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single shared {@link HttpClient} used for all outbound NCBI E-utilities calls.
 *
 * <p>One shared client means TCP/TLS connections are pooled and reused across requests instead of
 * each class holding its own client (master previously had three separate static clients) or
 * opening a fresh {@code URLConnection} per EFetch.
 *
 * <p><b>Merge note:</b> dev introduced this configuration class around Apache HttpClient 4
 * ({@code CloseableHttpClient}). Spring Boot 3 no longer manages HttpClient 4 and master's pom.xml
 * is intentionally unchanged, so the same idea is implemented with the JDK's built-in
 * {@code java.net.http.HttpClient} that master already uses. The per-request read bound lives in
 * {@link reciter.pubmed.NcbiHttp#REQUEST_TIMEOUT}.
 */
@Configuration
public class HttpClientConfig {

    /** Time to establish a TCP connection to NCBI. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public HttpClient pubMedHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // EFetch/ESearch answer directly; follow a same-scheme redirect if NCBI ever issues one.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
