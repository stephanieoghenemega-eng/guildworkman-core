package com.guildworkman.api.dto.requests;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class EditReviewRequest {
    private Long reviewId;
    private Long clientId;
    private String review;
}
