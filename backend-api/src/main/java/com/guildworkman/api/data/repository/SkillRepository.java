package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SkillRepository extends JpaRepository<Skill, Long> {
    Skill findSkillById(Long id);
    @Query("select skill from Skill skill where skill.skilledWorker.id=:skilledWorkerId")
    List<Skill> findSkillBySkillWorkerId(Long skilledWorkerId);
    @Query("select s from Skill s where s.skilledWorker.id=:skilledWorkerId")
    List<Skill> findSkillsFor(Long skilledWorkerId);
}
