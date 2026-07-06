package com.guildworkman.api.payment.services;

//import com.google.gson.JsonObject;
//import com.guildworkman.api.config.AppConfig;
//import com.guildworkman.api.payment.requests.PaymentRequest;
//import com.guildworkman.api.payment.responses.PaymentResponse;
//import com.guildworkman.api.payment.responses.PayStackData;
//import com.guildworkman.api.payment.responses.ResponseBodyDetails;
//import com.guildworkman.api.services.paystack.PaymentService;
//import okhttp3.mockwebserver.MockResponse;
//import okhttp3.mockwebserver.MockWebServer;
//import org.junit.jupiter.api.Assertions;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.Mock;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//
//import java.io.IOException;
//import java.math.BigDecimal;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//import static org.mockito.Mockito.when;
//
//@SpringBootTest
//public class PaymentServiceTest {
//    @Mock
//    private AppConfig appConfig;
//    @Autowired
//    private PaymentService paymentService;
//    private MockWebServer mockWebServer;
//
//    @BeforeEach
//    void setUp() throws IOException {
//        mockWebServer = new MockWebServer();
//        mockWebServer.start();
//    }
//
//    @Test
//    public void makePaymentTest(){
//        PaymentRequest request = new PaymentRequest();
//        request.setEmail("miishaqhyaro@gmail.com");
//        request.setAmount(BigDecimal.valueOf(7000.00));
//        PaymentResponse response = paymentService.makePayment(request);
//        assertThat(response).isNotNull();
//
//    }
//
//    @Test
//    public void initiatePaymentSuccessTest(){
//
//        when(appConfig.getPayStackInitiatePayment()).thenReturn(mockWebServer.url("/").toString());
//
//        JsonObject mockResponseBody = new JsonObject();
//        mockResponseBody.addProperty("status", "success");
//        mockResponseBody.addProperty("message", "Authorization Url created");
//
//        JsonObject data = new JsonObject();
//        data.addProperty("authorization_url", "https://checkout.paystack.com/mock_authorization_url");
//        data.addProperty("access_code", "mock_access_coe");
//        data.addProperty("reference", "mock_reference");
//
//        mockResponseBody.add("data", data);
//
//        MockResponse mockResponse = new MockResponse().setResponseCode(200).setBody(mockResponseBody.toString());
//        mockWebServer.enqueue(mockResponse);
//
//        PaymentRequest paymentRequest = new PaymentRequest();
//        paymentRequest.setEmail("test@gmail.com");
//        paymentRequest.setAmount(BigDecimal.valueOf(7000));
//
//        ResponseBodyDetails<?> responseBodyDetails = paymentService.initiatePayment(paymentRequest);
//
//        String expectedMessage = mockResponseBody.get("message").getAsString();
//        JsonObject expectedData = mockResponseBody.get("data").getAsJsonObject();
//        String expectedBaseUrl = "https://checkout.paystack.com/";
//        String expectedAccessCode = expectedData.get("access_code").getAsString();
//        String expectedReference = expectedData.get("reference").getAsString();
//
//        Assertions.assertNotNull(responseBodyDetails);
//        assertEquals(expectedMessage, responseBodyDetails.getMessage());
//
//        PayStackData actualData = (PayStackData) responseBodyDetails.getData();
//        Assertions.assertNotNull(actualData);
//
//        assertTrue(actualData.getAuthorizationUrl().startsWith(expectedBaseUrl));
//        Assertions.assertNotNull(actualData.getAccessCode());
//        Assertions.assertNotNull(actualData.getReference());
//
//    }
//
//}



import com.guildworkman.api.config.AppConfig;
import com.guildworkman.api.payment.requests.PaymentRequest;
import com.guildworkman.api.payment.responses.PaymentResponse;
import com.guildworkman.api.payment.responses.ResponseBodyDetails;
import com.guildworkman.api.services.paystack.PaymentServiceImpl;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
public class PaymentServiceImplTest {

    @Mock
    private AppConfig appConfig;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testMakePayment() {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setAmount(BigDecimal.valueOf(1000));
        paymentRequest.setEmail("test@example.com");

        String payStackUrl = "https://api.paystack.co/charge";
        when(appConfig.getPayStackInitiatePaymentUrl()).thenReturn(payStackUrl);

        PaymentResponse expectedResponse = new PaymentResponse();
        when(restTemplate.postForEntity(eq(payStackUrl), any(), eq(PaymentResponse.class)))
                .thenReturn(ResponseEntity.ok(expectedResponse));

        PaymentResponse actualResponse = paymentService.makePayment(paymentRequest);

        assertEquals(expectedResponse, actualResponse);
    }

    @Test
    public void testInitiatePayment() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setAmount(BigDecimal.valueOf(1000));
        paymentRequest.setEmail("test@example.com");

        OkHttpClient client = mock(OkHttpClient.class);
        Response response = mock(Response.class);
        when(response.isSuccessful()).thenReturn(true);
        when(response.body()).thenReturn(ResponseBody.create(
                "{\"status\": true, \"message\": \"Success\", \"data\": {\"authorization_url\": \"url\", \"access_code\": \"code\", \"reference\": \"ref\"}}",
                MediaType.parse("application/json")
        ));
        when(client.newCall(any(Request.class))).thenReturn(mock(Call.class));
        when(client.newCall(any(Request.class)).execute()).thenReturn(response);

        when(appConfig.getPayStackVerifyPaymentUrl()).thenReturn("https://api.paystack.co/verify/");
        when(appConfig.getPayStackSecretKey()).thenReturn("secretKey");

        ResponseBodyDetails<?> result = paymentService.initiatePayment(paymentRequest);

        assertNotNull(result);
        assertTrue(result.getStatus().equals("200"));
        assertEquals("Success", result.getMessage());
    }

    @Test
    public void testVerifyPayment() throws Exception {
        String reference = "test_reference";

        OkHttpClient client = mock(OkHttpClient.class);
        Response response = mock(Response.class);
        when(response.isSuccessful()).thenReturn(true);
        when(response.body()).thenReturn(ResponseBody.create(
                "{\"status\": true, \"message\": \"Verified\", \"data\": {}}",
                MediaType.parse("application/json")
        ));
        when(client.newCall(any(Request.class))).thenReturn(mock(Call.class));
        when(client.newCall(any(Request.class)).execute()).thenReturn(response);

        when(appConfig.getPayStackVerifyPaymentUrl()).thenReturn("https://api.paystack.co/verify/");
        when(appConfig.getPayStackSecretKey()).thenReturn("secretKey");

        ResponseBodyDetails<?> result = paymentService.verifyPayment(reference);

        assertNotNull(result);
        assertEquals("Verified", result.getMessage());
    }
}

