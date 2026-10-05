	package reciter.pubmed;

	import static org.junit.jupiter.api.Assertions.assertEquals;
	import static org.junit.jupiter.api.Assertions.assertSame;
	import static org.junit.jupiter.api.Assertions.assertThrows;

	import java.io.IOException;
	import java.io.InputStream;
	import java.net.Authenticator;
	import java.net.CookieHandler;
	import java.net.ProxySelector;
	import java.net.URI;
	import java.net.http.HttpClient;
	import java.net.http.HttpHeaders;
	import java.net.http.HttpRequest;
	import java.net.http.HttpResponse;
	import java.time.Duration;
	import java.util.ArrayDeque;
	import java.util.Deque;
	import java.util.List;
	import java.util.Map;
	import java.util.Optional;
	import java.util.concurrent.CompletableFuture;
	import java.util.concurrent.Executor;

	import javax.net.ssl.SSLContext;
	import javax.net.ssl.SSLParameters;
	import javax.net.ssl.SSLSession;

	import org.junit.jupiter.api.Test;

	class NcbiHttpTest {

		private static HttpRequest dummyRequest() {
			return HttpRequest.newBuilder().uri(URI.create("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"))
					.GET().build();
		}

		@Test
		void retriesTransientIOExceptionThenSucceeds() throws Exception {
			StubResponse okResponse = new StubResponse();
			Deque<Object> script = new ArrayDeque<>();
			script.add(new IOException("Connection reset"));
			script.add(new IOException("Connection reset"));
			script.add(okResponse);
			ScriptedHttpClient client = new ScriptedHttpClient(script);

			HttpResponse<InputStream> response = NcbiHttp.sendWithRetry(client, dummyRequest(), 4, null);

			assertSame(okResponse, response);
			assertEquals(3, client.sendCalls, "expected 2 failures + 1 success = 3 send() calls");
		}

		@Test
		void rethrowsAfterExhaustingAttempts() {
			Deque<Object> script = new ArrayDeque<>();
			script.add(new IOException("Connection reset 1"));
			script.add(new IOException("Connection reset 2"));
			script.add(new IOException("Connection reset 3"));
			script.add(new IOException("Connection reset 4"));
			ScriptedHttpClient client = new ScriptedHttpClient(script);

			IOException thrown = assertThrows(IOException.class,
					() -> NcbiHttp.sendWithRetry(client, dummyRequest(), 4, null));

			assertEquals("Connection reset 4", thrown.getMessage(), "should rethrow the LAST failure, not the first");
			assertEquals(4, client.sendCalls, "expected exactly maxAttempts (4) send() calls, no more");
		}

		/**
		 * HTTP 429 with Retry-After is a throttle, not a success: the helper must replay the request
		 * (after waiting the advertised interval) instead of handing the error page to the caller.
		 * Merged from dev's Retry-After handling (#117).
		 */
		@Test
		void throttledResponseIsReplayedAfterRetryAfter() throws Exception {
			StubResponse throttled = new StubResponse(429, Map.of("Retry-After", List.of("1")));
			StubResponse okResponse = new StubResponse();
			Deque<Object> script = new ArrayDeque<>();
			script.add(throttled);
			script.add(okResponse);
			ScriptedHttpClient client = new ScriptedHttpClient(script);

			HttpResponse<InputStream> response = NcbiHttp.sendWithRetry(client, dummyRequest(), 4, null);

			assertSame(okResponse, response);
			assertEquals(2, client.sendCalls, "expected 1 throttled + 1 successful send() call");
		}

		/** X-RateLimit-Remaining: 0 plus Retry-After is NCBI's other throttle signal. */
		@Test
		void rateLimitRemainingZeroWithRetryAfterIsReplayed() throws Exception {
			StubResponse exhausted = new StubResponse(200,
					Map.of("X-RateLimit-Remaining", List.of("0"), "Retry-After", List.of("1")));
			StubResponse okResponse = new StubResponse();
			Deque<Object> script = new ArrayDeque<>();
			script.add(exhausted);
			script.add(okResponse);
			ScriptedHttpClient client = new ScriptedHttpClient(script);

			assertSame(okResponse, NcbiHttp.sendWithRetry(client, dummyRequest(), 4, null));
			assertEquals(2, client.sendCalls);
		}

		/** A 5xx gateway error is transient and retried; a 4xx (other than 429) is returned as-is. */
		@Test
		void serverErrorIsRetriedButClientErrorIsReturned() throws Exception {
			StubResponse badGateway = new StubResponse(502, Map.of());
			StubResponse badRequest = new StubResponse(400, Map.of());
			Deque<Object> script = new ArrayDeque<>();
			script.add(badGateway);
			script.add(badRequest);
			ScriptedHttpClient client = new ScriptedHttpClient(script);

			HttpResponse<InputStream> response = NcbiHttp.sendWithRetry(client, dummyRequest(), 4, null);

			assertSame(badRequest, response);
			assertEquals(2, client.sendCalls);
		}

		/**
		 * Minimal {@link HttpClient} stub whose {@code send(...)} replays a scripted sequence of
		 * outcomes (either throw the next queued {@link IOException}, or return the next queued
		 * {@link HttpResponse}). Only {@code send} is exercised by {@link NcbiHttp}; the remaining
		 * abstract methods are never called by the code under test and just return harmless defaults.
		 */
		private static final class ScriptedHttpClient extends HttpClient {
			private final Deque<Object> script;
			int sendCalls = 0;

			ScriptedHttpClient(Deque<Object> script) {
				this.script = script;
			}

			@Override
			@SuppressWarnings("unchecked")
			public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
					throws IOException, InterruptedException {
				sendCalls++;
				Object next = script.poll();
				if (next instanceof IOException) {
					throw (IOException) next;
				}
				return (HttpResponse<T>) next;
			}

			@Override
			public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
					HttpResponse.BodyHandler<T> responseBodyHandler) {
				throw new UnsupportedOperationException("not used by NcbiHttp");
			}

			@Override
			public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
					HttpResponse.BodyHandler<T> responseBodyHandler,
					HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
				throw new UnsupportedOperationException("not used by NcbiHttp");
			}

			@Override
			public Optional<CookieHandler> cookieHandler() {
				return Optional.empty();
			}

			@Override
			public Optional<Duration> connectTimeout() {
				return Optional.empty();
			}

			@Override
			public Redirect followRedirects() {
				return Redirect.NEVER;
			}

			@Override
			public Optional<ProxySelector> proxy() {
				return Optional.empty();
			}

			@Override
			public SSLContext sslContext() {
				throw new UnsupportedOperationException("not used by NcbiHttp");
			}

			@Override
			public SSLParameters sslParameters() {
				return new SSLParameters();
			}

			@Override
			public Optional<Authenticator> authenticator() {
				return Optional.empty();
			}

			@Override
			public Version version() {
				return Version.HTTP_1_1;
			}

			@Override
			public Optional<Executor> executor() {
				return Optional.empty();
			}
		}

		/** Minimal {@link HttpResponse} stub with a configurable status and headers. */
		private static final class StubResponse implements HttpResponse<InputStream> {
			private final int status;
			private final Map<String, List<String>> headers;

			StubResponse() {
				this(200, Map.of());
			}

			StubResponse(int status, Map<String, List<String>> headers) {
				this.status = status;
				this.headers = headers;
			}

			@Override
			public int statusCode() {
				return status;
			}

			@Override
			public HttpRequest request() {
				return null;
			}

			@Override
			public Optional<HttpResponse<InputStream>> previousResponse() {
				return Optional.empty();
			}

			@Override
			public HttpHeaders headers() {
				return HttpHeaders.of(headers, (a, b) -> true);
			}

			@Override
			public InputStream body() {
				return InputStream.nullInputStream();
			}

			@Override
			public Optional<SSLSession> sslSession() {
				return Optional.empty();
			}

			@Override
			public URI uri() {
				return URI.create("https://eutils.ncbi.nlm.nih.gov/");
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_1_1;
			}
		}
	}
