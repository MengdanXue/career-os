package com.careeros;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
final class SpaForwardController {
    @GetMapping({
        "/",
        "/today",
        "/plan",
        "/jobs/{jobId:[^.]+}",
        "/opportunities",
        "/opportunities/{jobId:[^.]+}",
        "/updates",
        "/profile"
    })
    String forwardBrowserRoute() {
        return "forward:/index.html";
    }
}
