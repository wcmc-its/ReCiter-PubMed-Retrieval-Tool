package reciter.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bohnman.squiggly.Squiggly;
import com.github.bohnman.squiggly.util.SquigglyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.retriever.PubMedArticleRetriever;

@Controller
@RequestMapping("/pubmed")
public class PubMedRetrievalToolController {

	private static final Logger slf4jLogger = LoggerFactory.getLogger(PubMedRetrievalToolController.class);

	private List<PubMedArticle> retrieve(String query, String fields) throws IOException {
		query = URLEncoder.encode(query, "UTF-8");
		slf4jLogger.info("calling retrieve with query=[" + query + "]");
		List<PubMedArticle> pubMedArticles = new PubMedArticleRetriever().retrievePubMed(query);
		ObjectMapper objectMapper = Squiggly.init(new ObjectMapper(), fields);
		List<PubMedArticle> result = new ArrayList<>();
		objectMapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
		objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
		objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		pubMedArticles.forEach(elem -> {
			String partialObject = SquigglyUtils.stringify(objectMapper, elem);
			PubMedArticle pubMedArticle = null;
			try {
				pubMedArticle = objectMapper.readValue(partialObject, PubMedArticle.class);
			} catch (IOException e) {
				slf4jLogger.error("Unable to read value from pmid=" + elem.getMedlineCitation().getMedlineCitationPMID().getPmid(), e);
			}
			result.add(pubMedArticle);
		});
		slf4jLogger.info("retrieved " + pubMedArticles.size() + " PubMed articles using query=[" + query + "]");
		return result;
	}

	@RequestMapping(value = "/query/{query}", method = RequestMethod.GET)
	@ResponseBody
	public List<PubMedArticle> query(@PathVariable String query,
									 @RequestParam(name = "fields", required = false) String fields) throws IOException {
		return retrieve(query, fields);
	}

	@RequestMapping(value = "/pmid/{pmid}", method = RequestMethod.GET)
	@ResponseBody
	public List<PubMedArticle> pmid(@PathVariable long pmid, @RequestParam(name = "fields", required = false) String fields) throws RuntimeException {
		try {
			return retrieve(String.valueOf(pmid), fields);
		} catch (NumberFormatException e) {
			throw new RuntimeException("Unable to parse pmid '" + pmid + "'", e);
		} catch (IOException e) {
			throw new RuntimeException("IO Exception occurred.", e);
		}
	}
}
