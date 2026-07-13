package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.models.SkilledWorker;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class PostReviewRequest {
    private SkilledWorker skilledWorker;
    private String review;
}
