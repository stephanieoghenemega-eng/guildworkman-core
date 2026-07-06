package com.guildworkman.api.dto.responses;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class PostReviewResponse {
    private Long postId;
//    private Long clientId;
    private Long reviewerId;
    private String review;
    private LocalDateTime postedOn;
}
