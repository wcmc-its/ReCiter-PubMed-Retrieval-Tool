package reciter.swagger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import static springfox.documentation.builders.PathSelectors.regex;

@Configuration
@EnableSwagger2
public class SwaggerConfig {
    @Bean
    public Docket productApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .select()
                .apis(RequestHandlerSelectors.basePackage("reciter.controller"))
                .paths(regex("/pubmed.*"))
                .build()
                .apiInfo(apiInfo());
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder().title("ReCiter publication management system - PubMed Retrieval Tool")
                .description("Retrieve publications and publication counts from PubMed. More info here: https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/").termsOfServiceUrl("")
                .contact(new Contact("Paul J. Albert", "https://github.com/wcmc-its/ReCiter", "paa2013@med.cornell.edu"))
                .contact(new Contact("Sarbajit Dutta", "https://github.com/wcmc-its/ReCiter", "szd2013@med.cornell.edu"))
                .license("Apache License Version 2.0")
                .licenseUrl("https://www.apache.org/licenses/LICENSE-2.0")
                .version("1.1.0")
                .build();
    }
}
