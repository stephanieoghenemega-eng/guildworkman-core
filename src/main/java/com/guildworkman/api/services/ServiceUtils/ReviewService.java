package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.dto.requests.PostReviewRequest;
import com.guildworkman.api.dto.responses.PostReviewResponse;

public interface ReviewService {
    PostReviewResponse addReview(PostReviewRequest postReview);
}
