package com.guildworkman.api.dto.responses;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class EditReviewResponse {
    private Long reviewId;
    private Long reviewerId;
    private Long clientId;
    private String review;
}
