package com.example.consumer.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;

@Configuration
public class BeanConfig {

//    @Bean
//    @LoadBalanced
//    RestOperations restTemplate(RestTemplateBuilder builder) {
//        return builder.build();
//    }



    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
     }
//     @Bean
//    public ClientHttpRequestFactory clientHttpRequestFactory(){
//        return new ClientHttpRequestFactory() {
//            @Override
//            public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
//                return null;
//            }
//        };
//    }
}
