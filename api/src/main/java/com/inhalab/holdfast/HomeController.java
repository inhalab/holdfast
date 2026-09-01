package com.inhalab.holdfast;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @Value("${holdfast.instance-id:unknown}")
    private String instanceId;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("instanceId", instanceId);
        return "home";
    }
}
