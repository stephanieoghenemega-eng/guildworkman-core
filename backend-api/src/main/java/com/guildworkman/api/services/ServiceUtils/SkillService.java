package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.dto.requests.AddSkillRequest;
import com.guildworkman.api.dto.responses.AddSkillResponse;
import com.guildworkman.api.data.models.Skill;

import java.util.List;

public interface SkillService {
    AddSkillResponse addSkill(AddSkillRequest addSkillRequest);
    Skill findSkillById(Long skilledWorkerId);
    Skill addASkill(Skill skill);

    List<Skill> findSkillFor(Long skilledWorkerId);
}
