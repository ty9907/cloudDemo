package com.example.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

@EnableEurekaClient
@SpringBootApplication
public class Cloud01Application {

	public static void main(String[] args) {
		SpringApplication.run(Cloud01Application.class, args);
	}

}
