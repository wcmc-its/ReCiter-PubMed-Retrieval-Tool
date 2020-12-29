package reciter.controller;


import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/pubmed")
@Api(value = "PingController", description = "Health Check.")
public class PingController {

    @ApiOperation(value = "Health check", response = ResponseEntity.class)
    @RequestMapping(value = "/ping", method = RequestMethod.GET, produces = "text/plain")
    @ResponseBody
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Healthy");
    }
}
