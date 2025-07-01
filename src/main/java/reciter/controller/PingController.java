package reciter.controller;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/pubmed")
@Tag(name = "PingController", description = "Health Check.")
public class PingController {

	@Operation(summary  = "Health check")
    @GetMapping(value = "/ping", produces = "text/plain")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Healthy");
    }
}
