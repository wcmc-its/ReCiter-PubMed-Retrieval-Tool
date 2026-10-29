package reciter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

import reciter.pubmed.retriever.RetrievalThresholdExceededException;

/**
 * Logs Spring Retry activity for {@code PubMedArticleRetrievalService.retrieve}.
 *
 * <p>Merge note: master's listener (Spring Retry 2 {@link RetryListener} interface) is kept; dev's
 * {@code RetryListener extends RetryListenerSupport} is dropped because that base class is deprecated
 * in Spring Retry 2 and both were registered as the same "retryListener" bean.
 * {@link RetrievalThresholdExceededException} (merged from dev) is an expected, permanent refusal
 * of an over-broad query — it is never retried and is answered with a 502 — so it is logged as a
 * one-line WARN rather than an ERROR with a stack trace, keeping CloudWatch free of false alarms.
 */
@Component("retryListener")
public class ReciterRetryListener implements RetryListener {

	private static final Logger log = LoggerFactory.getLogger(ReciterRetryListener.class);

	@Override
	public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback,
			Throwable throwable) {

		if (throwable instanceof RetrievalThresholdExceededException) {
			return; // already logged once in onError; not a retry failure
		}
		if (throwable != null) {
			log.error("Retry exhausted after {} attempt(s). Final exception: {}: {}", context.getRetryCount(),
					throwable.getClass().getName(), throwable.getMessage(), throwable);
		}
	}

	@Override
	public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
			Throwable throwable) {

		if (throwable instanceof RetrievalThresholdExceededException) {
			log.warn("Query refused without retry: {}", throwable.getMessage());
			return;
		}
		log.error("Exception occurred, Retry Count {} with error: {}", context.getRetryCount(), throwable.getMessage(),
				throwable);
	}

	@Override
	public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
		return true;
	}
}
