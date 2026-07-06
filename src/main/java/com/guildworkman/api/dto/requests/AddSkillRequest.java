package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.constants.Category;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AddSkillRequest {
    private Long skillId;
    private Long skilledWorkerId;
    private String skillName;
    private Category category;

}
