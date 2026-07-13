//package com.guildworkman.api.controllers;
//
//import com.guildworkman.api.dto.requests.BookAppointmentRequest;
//import com.guildworkman.api.dto.requests.PaymentRequest;
//import com.guildworkman.api.dto.responses.ApiResponse;
//import com.guildworkman.api.services.paystack.PaymentService;
//import lombok.AllArgsConstructor;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.RequestEntity;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import static org.springframework.http.HttpStatus.CREATED;
//
//@RestController
//@RequestMapping("/payment")
//@AllArgsConstructor
//public class PaymentController {
//
//    private final PaymentService paymentService;
//
//    @PostMapping("/payment")
//    public ResponseEntity<?> payment(@RequestBody PaymentRequest paymentRequest){
//        return ResponseEntity.status(CREATED)
//                .body(new ApiResponse(paymentService.makePayment(paymentRequest),true));
//}
//
//}