package reciter.pubmed.retriever;

import java.io.IOException;

/**
 * What a failed retrieval throws once Spring Retry has given up on it.
 *
 * <p><strong>UNCHECKED, AND IT HAS TO BE. THIS IS THE 500-INSTEAD-OF-502 BUG.</strong>
 *
 * <p>Spring Retry invokes a {@code @Recover} method <em>reflectively</em>
 * ({@code RecoverAnnotationRecoveryHandler} → {@code ReflectionUtils.invokeMethod}). When a
 * reflectively-invoked method throws a <em>checked</em> exception, {@code ReflectionUtils} wraps it
 * in a {@link java.lang.reflect.UndeclaredThrowableException} — which is a {@code RuntimeException}.
 * So a {@code @Recover} that rethrew the original {@link IOException} produced this:
 *
 * <pre>
 *   UndeclaredThrowableException          &lt;- what Spring MVC actually sees
 *     └─ caused by IOException("... exceeded the threshold level 2000")
 * </pre>
 *
 * <p>{@code @ExceptionHandler(IOException.class)} therefore never saw it, the catch-all
 * {@code @ExceptionHandler(Exception.class)} caught it instead, and a deliberate, well-formed
 * "your query is too broad" refusal was served to the caller as <strong>500 internal_error — "An
 * unexpected error occurred"</strong>. The 502 mapping had been dead the whole time, and it was
 * invisible because the log line looked correct.
 *
 * <p>Throwing an UNCHECKED exception from {@code @Recover} is what keeps the type intact across
 * that reflective boundary, so the handler can see it and classify it. Do not "simplify" this back
 * into a checked rethrow.
 */
public class PubMedRetrievalException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PubMedRetrievalException(IOException cause) {
		super(cause.getMessage(), cause);
	}
}
