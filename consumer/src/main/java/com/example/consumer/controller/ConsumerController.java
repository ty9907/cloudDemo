package com.example.consumer.controller;

import com.example.consumer.hystrix.MyHystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
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
    private RestTemplate restTemplate;

    @Autowired
    private DiscoveryClient discoveryClient;

    @HystrixCommand(fallbackMethod = "error",commandProperties={@HystrixProperty(name="execution.isolation.thread.timeoutInMilliseconds",value="1500")})
    @RequestMapping("/consumer")
    public String consumer() {
//        int i=1/0;
        return restTemplate.getForObject("http://provider-a/hello", String.class);
//        return restTemplate.getForEntity("http://localhost:8080/hello",String.class).getBody();
    }

    @RequestMapping("/hystrix")
    public String hystrix() {
        MyHystrixCommand hystrixCommand=new MyHystrixCommand(com.netflix.hystrix.HystrixCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("")),
                restTemplate);
        String str = hystrixCommand.execute();
        return str;
    }


    public String  error(Throwable throwable){
        return "error"+throwable.getMessage();
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
        return restTemplate.getForEntity("http://provider2/hello", String.class).getBody();

    }
}