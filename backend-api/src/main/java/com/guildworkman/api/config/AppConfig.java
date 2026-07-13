package com.guildworkman.api.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Value("${paystack.secret.key}")
    private String payStackSecretKey;
    @Value("${paystack.verify.payment.url}")
    private String payStackVerifyPaymentUrl;
    @Value("${paystack.initiate.payment}")
    private String payStackInitiatePaymentUrl;

    public String getPayStackSecretKey() {
        return payStackSecretKey;
    }

    public String getPayStackVerifyPaymentUrl() {
        return payStackVerifyPaymentUrl;
    }

    public String getPayStackInitiatePaymentUrl() {
        return payStackInitiatePaymentUrl;
    }

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
