package dev.pgcp.cp.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Client-side routes of the single-page app are served by index.html. */
@Controller
public class SpaController {

    @GetMapping({ "/", "/login", "/setup", "/servers/**", "/instances/**", "/databases/**", "/roles/**", "/jobs/**",
            "/activity/**", "/settings/**", "/backups/**", "/branches/**", "/restore/**" })
    public String index() {
        return "forward:/index.html";
    }
}
