package com.guildworkman.api.services.paystack;


import com.guildworkman.api.payment.requests.PaymentRequest;
import com.guildworkman.api.payment.responses.PaymentResponse;
import com.guildworkman.api.payment.responses.ResponseBodyDetails;

public interface PaymentService {

    PaymentResponse makePayment(PaymentRequest paymentRequest);

    ResponseBodyDetails<?> initiatePayment(PaymentRequest paymentRequest);

    ResponseBodyDetails<?> verifyPayment(String reference);

}