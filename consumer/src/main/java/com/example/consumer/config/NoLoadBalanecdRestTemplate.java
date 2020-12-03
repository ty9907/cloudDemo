package com.example.consumer.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;

@Configuration
public class NoLoadBalanecdRestTemplate extends RestTemplate {

    @Autowired
    private ClientHttpRequestFactory  clientHttpRequestFactory;



    @Bean
    public NoLoadBalanecdRestTemplate noLoadRestTemplate(){
        return new NoLoadBalanecdRestTemplate();
    }
}
