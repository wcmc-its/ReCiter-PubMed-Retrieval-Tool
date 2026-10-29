package reciter.pubmed.retriever;

import java.io.IOException;

/**
 * The query matched more PubMed articles than this tool is willing to retrieve.
 *
 * <p><strong>This is a PERMANENT condition, and that is the whole reason the type exists.</strong>
 * The matched count is a property of the query, not of the network: retrying it cannot make the
 * number smaller. It was previously thrown as a plain {@link IOException} from inside a
 * {@code @Retryable} method whose retry policy is {@code IOException}, so a too-broad query was
 * retried <strong>seven times</strong>, with backoff — seven pointless ESearch calls to NCBI, whose
 * unkeyed rate limit is 3 requests/second, for an answer that was never going to change. Giving the
 * refusal its own type is what lets {@code @Retryable} exclude it and fail on the first attempt.
 *
 * <p>It stays an {@link IOException} subclass so that every existing {@code throws IOException}
 * signature and {@code catch (IOException)} block continues to see it, and so that
 * {@code GlobalExceptionHandler}'s message-marker match keeps working unchanged.
 */
public class RetrievalThresholdExceededException extends IOException {

	private static final long serialVersionUID = 1L;

	public RetrievalThresholdExceededException(String message) {
		super(message);
	}
}
