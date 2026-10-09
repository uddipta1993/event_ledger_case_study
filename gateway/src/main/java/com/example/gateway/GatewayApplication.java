package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class,args); }
    @Bean public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory(); f.setConnectTimeout(800); f.setReadTimeout(1200); return new RestTemplate(f);
    }
}
