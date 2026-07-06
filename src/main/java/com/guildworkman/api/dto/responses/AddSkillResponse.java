package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.constants.Category;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AddSkillResponse {
    private Long skillId;
    private Long skilledWorkerId;
    private Category category;
    private String skillName;
}
