package com.skilledservice.ClientService.services.implmentations;

import com.skilledservice.ClientService.dto.requests.PostReviewRequest;
import com.skilledservice.ClientService.dto.responses.PostReviewResponse;
import com.skilledservice.ClientService.data.models.Review;
import com.skilledservice.ClientService.data.models.SkilledWorker;
import com.skilledservice.ClientService.data.repository.ReviewRepository;
import com.skilledservice.ClientService.exceptions.GuildWorkmanException;
import com.skilledservice.ClientService.services.ServiceUtils.ReviewService;
import com.skilledservice.ClientService.services.ServiceUtils.SkilledWorkerService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ReviewServiceImpl implements ReviewService {

    private final SkilledWorkerService skilledWorkerService;
    private final ReviewRepository reviewRepository;

    public ReviewServiceImpl(SkilledWorkerService skilledWorkerService, ReviewRepository reviewRepository) {

        this.skilledWorkerService = skilledWorkerService;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public PostReviewResponse addReview(PostReviewRequest postReview) {

        Review review = new Review();
        SkilledWorker skilledWorker = skilledWorkerService.findById(postReview.getSkilledWorker().getId());
        if (skilledWorker == null) throw new GuildWorkmanException("skilled worker not found");
        review.setSkilledWorker(skilledWorker);
        review.setReview(postReview.getReview());
        review.setReviewDate(LocalDateTime.now());

        review = reviewRepository.save(review);

        PostReviewResponse response = new PostReviewResponse();

        response.setReview(review.getReview());
        response.setReviewerId(review.getClientId());
        response.setPostId(review.getId());
        response.setPostedOn(review.getReviewDate());

        return response;
    }
}
