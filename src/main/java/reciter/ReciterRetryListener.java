package reciter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

@Component("retryListener") 
public class ReciterRetryListener implements RetryListener {
	
	private static final Logger log = LoggerFactory.getLogger(ReciterRetryListener.class);
	@Override
	public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback,
			Throwable throwable) {

		if (throwable != null) {
			log.error("Retry exhausted. Final exception:", throwable);
		}
	}

	@Override
	public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
			Throwable throwable) {

		log.error("Exception occurred, Retry Count {} with error: {}", context.getRetryCount(), throwable.getMessage(),
				throwable);
	}

	@Override
	public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
		return true;
	}
}
