package com.example.common.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.ws.rs.POST;

@RestController
public class HelloController {


    @RequestMapping("/hello")
    public String hello() throws InterruptedException {
        Thread.sleep(2000);
        return "hello.cloud1111";
    }


    @RequestMapping(value = "/posthello",method = RequestMethod.POST)
    public String posthello(){
        return "hello.cloud,provider1";
    }
}
