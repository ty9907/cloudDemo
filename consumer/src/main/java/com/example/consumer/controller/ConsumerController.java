package com.example.consumer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ConsumerController {

    @Autowired
    private RestOperations restTemplate1;

    @Autowired
    private DiscoveryClient discoveryClient;

    @RequestMapping("/consumer")
    public String consumer() {
        return restTemplate1.getForEntity("http://provider2:9002/hello", String.class).getBody();
//        return restTemplate.getForEntity("http://localhost:8080/hello",String.class).getBody();
    }


    @GetMapping("/getInstance")
    public Map<String, List<ServiceInstance>> serviceUrl() {
        Map<String, List<ServiceInstance>> msl = new HashMap<>();
        List<String> services = discoveryClient.getServices();
        for (String service : services) {
            List<ServiceInstance> sis = discoveryClient.getInstances(service);
            msl.put(service, sis);
        }
        return msl;
    }

    @RequestMapping("/consumer2")
    @ResponseBody
    public String querybysql2() {
        return restTemplate1.getForEntity("http://provider2/hello", String.class).getBody();

    }
}