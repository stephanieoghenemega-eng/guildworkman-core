//package com.guildworkman.api.services;
//
//import com.guildworkman.api.data.models.SkilledWorker;
//import com.guildworkman.api.dto.requests.PostReviewRequest;
//import com.guildworkman.api.dto.requests.RegistrationRequest;
//import com.guildworkman.api.dto.responses.ClientRegistrationResponse;
//import com.guildworkman.api.dto.responses.PostReviewResponse;
//import com.guildworkman.api.dto.responses.SkilledWorkerRegistrationResponse;
//import com.guildworkman.api.exceptions.GuildWorkmanException;
//import com.guildworkman.api.data.models.Address;
//import com.guildworkman.api.data.repository.AddressRepository;
//import com.guildworkman.api.data.repository.ReviewRepository;
//import com.guildworkman.api.data.repository.ClientRepository;
//import com.guildworkman.api.services.ServiceUtils.ClientService;
//import com.guildworkman.api.services.ServiceUtils.ReviewService;
//import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.jdbc.Sql;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//
//@SpringBootTest
//@Sql(scripts = {"/db/data.sql"})
//public class ReviewServiceTest {
//    @Autowired
//    private SkilledWorkerService skilledWorkerService;
//    @Autowired
//    private ClientService clientService;
//    @Autowired
//    private ReviewRepository reviewRepository;
//    @Autowired
//    private ReviewService reviewService;
//
//
//    @Test
//    public void postReviewForSkilledWorkerTest() {
//        RegistrationRequest registrationRequest = getRegisterSkilledWorkerRequest();
//        SkilledWorkerRegistrationResponse response = skilledWorkerService.registerSkilledWorker(registrationRequest);
//        Long skilledWorkerId = response.getSkilledWorkerId();
//        assertThat(skilledWorkerId).isNotNull();
//        assertThat(skilledWorkerService.getNumberOfUsers()).isEqualTo(1L);
//
//        RegistrationRequest registerClientRequest = getRegisterClientRequest();
//        ClientRegistrationResponse clientResponse = clientService.registerClient(registerClientRequest);
//        Long clientId = clientResponse.getClientId();
//        assertThat(clientId).isNotNull();
//        assertThat(clientService.getNumberOfUsers()).isEqualTo(1L);
//
//        PostReviewRequest postReviewRequest = new PostReviewRequest();
//        SkilledWorker skilledWorker = skilledWorkerService.findById(skilledWorkerId);
//        postReviewRequest.setSkilledWorker(skilledWorker);
//        postReviewRequest.setReview("impressive work ethic");
//
//        PostReviewResponse reviewResponse = reviewService.addReview(postReviewRequest);
//
//        assertThat(reviewResponse).isNotNull();
//        assertThat(reviewResponse.getReview()).isEqualTo("impressive work ethic");
//        assertThat(reviewResponse.getReviewerId());
//        var review = reviewRepository.findById(reviewResponse.getPostId())
//                .orElseThrow(() -> new GuildWorkmanException("Review not found"));
//        assertThat(review.getReview()).isEqualTo("impressive work ethic");
//        assertThat(reviewRepository.count()).isEqualTo(1L);
//
//    }
//
//
//    private static RegistrationRequest getRegisterClientRequest() {
//        RegistrationRequest registerClientRequest = new RegistrationRequest();
//        registerClientRequest.setFirstName("John");
//        registerClientRequest.setLastName("Doe");
//        registerClientRequest.setUsername("JohnDoe");
//        registerClientRequest.setPhoneNumber("123456789");
//        registerClientRequest.setEmail("john@doe.com");
//        registerClientRequest.setStreet("Street");
//        registerClientRequest.setArea("area");
//        registerClientRequest.setHouseNumber("number");
//        registerClientRequest.setPassword("password");
//
//        return registerClientRequest;
//    }
//
//    private RegistrationRequest getRegisterSkilledWorkerRequest() {
//        RegistrationRequest registrationRequest = new RegistrationRequest();
//
//        registrationRequest.setHouseNumber("312");
//        registrationRequest.setStreet("Herbert Macaulay Way");
//        registrationRequest.setArea("Yaba");
//        registrationRequest.setFirstName("Fitzgerald");
//        registrationRequest.setLastName("McDonald");
//        registrationRequest.setUsername("FitzG");
//        registrationRequest.setEmail("fitzgerald@gmail.com");
//        registrationRequest.setPassword("password");
//        registrationRequest.setPhoneNumber("1234567890");
//
//        return registrationRequest;
//    }
//
//
//}
