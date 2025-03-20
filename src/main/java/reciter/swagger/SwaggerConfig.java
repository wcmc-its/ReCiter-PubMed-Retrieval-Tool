package reciter.swagger;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;


@Configuration
public class SwaggerConfig {
    
    @Bean
	public GroupedOpenApi productApi() {
		return GroupedOpenApi.builder().group("reciter-group").packagesToScan("reciter.controller")
				.pathsToMatch("/pubmed.*").build();
	}

	@Bean
	public OpenAPI apiInfo() {
		return new OpenAPI().info(new Info().title("ReCiter publication management system - PubMed Retrieval Tool")
				.version("1.1.0")
				.contact(new Contact().name("Paul J. Albert").url("https://github.com/wcmc-its/ReCiter")
						.email("paa2013@med.cornell.edu"))
				.description(
						"Retrieve publications and publication counts from PubMed. More info here: https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/"));
	}
}
