package uk.ac.ebi.tsc.tesk.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Home redirection to swagger api documentation
 */
@Controller
public class HomeController {

    private final static Logger logger = LoggerFactory.getLogger(HomeController.class);

    @RequestMapping(value = "/")
    public String index() {
        logger.info("Redirecting from / to swagger-ui.html");

        return "redirect:swagger-ui.html";
    }
}