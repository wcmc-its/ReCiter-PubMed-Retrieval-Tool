/*
 * package reciter;
 * 
 * import org.springframework.context.annotation.Bean; import
 * org.springframework.context.annotation.Configuration; import
 * org.springframework.core.Ordered; import
 * org.springframework.core.annotation.Order; import
 * org.springframework.web.filter.ForwardedHeaderFilter; import
 * org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
 * 
 * @Configuration public class ProxyConfig {
 * 
 * @Bean
 * 
 * @ConditionalOnProperty(name = "server.forward-headers-strategy",
 * havingValue="framework")
 * 
 * @Order(Ordered.HIGHEST_PRECEDENCE) public ForwardedHeaderFilter
 * forwardedHeaderFilter() { // This filter ensures Spring correctly processes
 * X-Forwarded-Host, X-Forwarded-Proto, etc. return new ForwardedHeaderFilter();
 * } }
 */