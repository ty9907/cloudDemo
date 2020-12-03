package com.example.consumer.controller;

import com.example.consumer.config.NoLoadBalanecdRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class ConsumerController {

    @Autowired
    private RestTemplate restTemplate;
//
//    @Autowired
//    private NoLoadBalanecdRestTemplate noLoadRestTemplate;

    @RequestMapping("/consumer")
    public String consumer() {
        return restTemplate.getForEntity("http://provider/hello", String.class).getBody();
//        return restTemplate.getForEntity("http://localhost:8080/hello",String.class).getBody();
    }


    @RequestMapping("/consumer2")
    @ResponseBody
    public String querybysql2() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(5000);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
//        JSONObject postData = new JSONObject();
        //带参数 ❤♋ 分割
        //postData.put("sql", "select * from products where name like ? and factory like ? ");
        //postData.put("args", "%阿莫西林%❤♋四川%");

//        postData.put("sql", "select * from products where name like '%阿莫西林%' and factory like '四川%' ");

        return restTemplate.getForEntity("http://192.168.0.100:2100/drugshop/querybysql", String.class).getBody();


    }
}