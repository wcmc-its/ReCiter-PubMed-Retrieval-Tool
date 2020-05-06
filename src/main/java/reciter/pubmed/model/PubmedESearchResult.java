package reciter.pubmed.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
//@JsonRootName(value = "esearchresult")
public class PubmedESearchResult {
	
	@JsonProperty(value = "count", required = true)
	private int count;
	@JsonProperty(value = "retmax", required = true)
	private int retMax;
	@JsonProperty(value = "retstart", required = true)
	private int retStart;
	@JsonProperty(value = "querykey", required = true)
	private int queryKey;
	@JsonProperty(value = "webenv", required = true)
	private String webenv;
}
