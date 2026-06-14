package reciter.swagger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

/**
 * OpenAPI/Swagger configuration via springdoc-openapi (#120, replacing the abandoned Springfox).
 *
 * <p>springdoc auto-detects the controller request mappings, so no Docket/path selection is needed:
 * every endpoint in this service is under {@code /pubmed}. This bean only supplies the API metadata.
 * Swagger UI is served at {@code /swagger-ui/index.html} and the OpenAPI JSON at {@code /v3/api-docs}.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI pubMedRetrievalToolOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("ReCiter publication management system - PubMed Retrieval Tool")
                .description("Retrieve publications and publication counts from PubMed. More info here: "
                        + "https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/")
                .version("1.1.0")
                .contact(new Contact()
                        .name("Paul J. Albert")
                        .url("https://github.com/wcmc-its/ReCiter")
                        .email("paa2013@med.cornell.edu"))
                .license(new License()
                        .name("Apache License Version 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
