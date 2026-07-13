package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.dto.requests.PostReviewRequest;
import com.guildworkman.api.dto.responses.PostReviewResponse;
import com.guildworkman.api.data.models.Review;
import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.data.repository.ReviewRepository;
import com.guildworkman.api.exceptions.GuildWorkmanException;
import com.guildworkman.api.services.ServiceUtils.ReviewService;
import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;
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
