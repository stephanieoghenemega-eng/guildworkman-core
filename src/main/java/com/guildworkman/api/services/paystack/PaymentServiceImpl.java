package com.guildworkman.api.services.paystack;

import com.guildworkman.api.config.AppConfig;
import com.guildworkman.api.payment.requests.PaymentRequest;
import com.guildworkman.api.payment.responses.PaymentResponse;
import com.guildworkman.api.payment.responses.PayStackData;
import com.guildworkman.api.payment.responses.ResponseBodyDetails;
import okhttp3.*;
import org.cloudinary.json.JSONObject;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final AppConfig appConfig;

    public PaymentServiceImpl(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    public PaymentResponse makePayment(PaymentRequest paymentRequest) {
        String authorization = "Bearer " + appConfig.getPayStackSecretKey();
        String payStackUrl = appConfig.getPayStackInitiatePaymentUrl();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return getPaymentResponse(paymentRequest, headers, payStackUrl);
    }

    @Override
    public ResponseBodyDetails<?> initiatePayment(PaymentRequest paymentRequest) {
        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = new FormBody.Builder()
               .add("amount", String.valueOf(paymentRequest.getAmount()))
               .add("email", paymentRequest.getEmail())
               .build();
        System.out.println(appConfig.getPayStackVerifyPaymentUrl());

        return getResponseBodyDetails(requestBody, client);
    }


    private ResponseBodyDetails<?> convertStringToJsonObject(String jsonResponse) {
        JSONObject jsonObject = new JSONObject(jsonResponse);

        boolean status = jsonObject.getBoolean("status");
        String message = jsonObject.getString("message");
        JSONObject data = jsonObject.getJSONObject("data");

        String authorizationUrl = data.getString("authorization_url");
        String accessCode = data.getString("access_code");
        String reference = data.getString("reference");

        PayStackData payStackData = PayStackData.builder().authorizationUrl(authorizationUrl).accessCode(accessCode).reference(reference).build();
        return ResponseBodyDetails.builder().message(message).status(String.valueOf(200)).data(payStackData).build();
    }

    @Override
    public ResponseBodyDetails<?> verifyPayment(String reference) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(appConfig.getPayStackVerifyPaymentUrl() + reference)
                .header("Authorization", "Bearer " + appConfig.getPayStackSecretKey())
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                assert response.body() != null;
                throw new Exception("PayStack payment verification request field: " + response.body().string());
            }
            assert response.body() != null;
            String responseBody = response.body().string();
            return ResponseBodyDetails.builder().message("PayStack Payment verification request failed: " + response).build();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error while initiating payment request --> " + e.getMessage());
        }

    }

    private ResponseBodyDetails<?> getResponseBodyDetails(RequestBody requestBody, OkHttpClient client) {
        Request request = new Request.Builder()
                .url(appConfig.getPayStackVerifyPaymentUrl())
                .header("Authorization", "Bearer " + appConfig.getPayStackSecretKey())
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                assert response.body() != null;
                throw new Exception("PayStack request failed: " + response.body().string());
            }
            assert  response.body() != null;

            String jsonResponse = response.body().string();
            return convertStringToJsonObject(jsonResponse);
        }catch(Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error occurred while initiating payment request --> " + e.getMessage());
        }
    }

    private static @Nullable PaymentResponse getPaymentResponse(PaymentRequest paymentRequest, HttpHeaders headers, String payStackUrl) {
        HttpEntity<PaymentRequest> request = new HttpEntity<>(paymentRequest, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<PaymentResponse> response = restTemplate.postForEntity(payStackUrl, request, PaymentResponse.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            System.err.println("HTTP Error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            throw e;
        } catch (Exception e) {
            System.err.println("Error making payment request: " + e.getMessage());
            throw new RuntimeException("Failed to make payment request", e);
        }
    }
}
