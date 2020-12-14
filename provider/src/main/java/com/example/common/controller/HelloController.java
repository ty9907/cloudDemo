package com.example.common.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.ws.rs.POST;

@RestController
public class HelloController {


    @RequestMapping("/hello")
    public String hello(){
        return "hello.cloud";
    }


    @RequestMapping(value = "/posthello",method = RequestMethod.POST)
    public String posthello(){
        return "hello.cloud";
    }
}
