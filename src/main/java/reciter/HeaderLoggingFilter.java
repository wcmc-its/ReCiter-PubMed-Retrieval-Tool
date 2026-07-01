package reciter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

// The @Component annotation ensures Spring automatically registers this filter
@Component
public class HeaderLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(HeaderLoggingFilter.class);
    
    // ── Constant avoids repeated toLowerCase() / equalsIgnoreCase() per header per request ──
    private static final String X_FORWARDED_PROTO = "x-forwarded-proto";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        // Cast to HttpServletRequest to access header methods
        HttpServletRequest req = (HttpServletRequest) request;
        
        log.info("--- Incoming Request Header Dump ---");
        log.info("Request URI: {}", req.getRequestURI());
        
        // Iterate through all header names and log their values
        for (String headerName : Collections.list(req.getHeaderNames())) {
            if (headerName.equalsIgnoreCase(X_FORWARDED_PROTO)) {
                log.error("!!! CRITICAL HEADER: {} = {}", headerName, req.getHeader(headerName));
            } else {
                log.info("{}: {}", headerName, req.getHeader(headerName));
            }
        }
        log.info("----------------------------------");

        // Proceed to the next filter in the chain (e.g., your controllers)
        chain.doFilter(request, response);
    }
}