package reciter.controller;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import reciter.model.pubmed.PubMedArticle;
import reciter.pubmed.retriever.PubMedArticleRetriever;

@Controller
public class PubMedRetrievalToolController {

	private static final Logger slf4jLogger = LoggerFactory.getLogger(PubMedRetrievalToolController.class);

	@RequestMapping(value = "/reciter/retrieve/pubmed/by/query", method = RequestMethod.GET)
	@ResponseBody
	public List<PubMedArticle> retrieve(@RequestParam(value="query") String query) throws IOException {

		slf4jLogger.info("calling retrieve with query=[" + query + "]");
		List<PubMedArticle> pubMedArticles = new PubMedArticleRetriever().retrievePubMed(query);
		slf4jLogger.info("retrieved " + pubMedArticles.size() + " PubMed articles using query=[" + query + "]");
		return pubMedArticles;
	}
}
