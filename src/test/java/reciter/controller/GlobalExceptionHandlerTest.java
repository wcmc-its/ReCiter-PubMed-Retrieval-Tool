package reciter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reciter.pubmed.retriever.PubMedArticleRetrievalService;

/**
 * A missing or malformed request body is a CLIENT error and must be answered with 400, not by the
 * catch-all handler as a 500 "internal_error".
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        PubMedRetrievalToolController controller = new PubMedRetrievalToolController();
        ReflectionTestUtils.setField(controller, "pubMedArticleRetrievalService",
                new PubMedArticleRetrievalService(null, null));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingBodyOnCountEndpointIs400() throws Exception {
        mvc.perform(post("/pubmed/query-number-pubmed-articles/").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void missingBodyOnQueryComplexIs400() throws Exception {
        mvc.perform(post("/pubmed/query-complex/").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void malformedJsonBodyIs400() throws Exception {
        mvc.perform(post("/pubmed/query-number-pubmed-articles/")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }
}
