package reciter.controller;

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
public class ReCiterController {

	private static final Logger slf4jLogger = LoggerFactory.getLogger(ReCiterController.class);

	@RequestMapping(value = "/reciter/retrieve/pubmed/by/query", method = RequestMethod.GET)
	@ResponseBody
	public List<PubMedArticle> retrieve(
			@RequestParam(value="cwid") String query, 
			@RequestParam(value="numberOfPubmedArticles") int numberOfPubmedArticles) {

		slf4jLogger.info("calling retrieve with query=[" + query + " numberOfPubmedArticles=[" + numberOfPubmedArticles);
		PubMedArticleRetriever pubMedArticleRetriever = new PubMedArticleRetriever();
		List<PubMedArticle> pubMedArticles = pubMedArticleRetriever.retrievePubMed(query, numberOfPubmedArticles);
		slf4jLogger.info("retrieved " + pubMedArticles.size() + " PubMed articles using query=[" + query + "]");
		return pubMedArticles;
	}
}
