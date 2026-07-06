package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {
//    @Query("select r Review from Review r where r.skilledWorker.id=:reviewerId")
//    List<Review> findReviewsForSkilledWorker(Long reviewerId);
//
}

//@Query("select s from Skill s where s.skilledWorker.id=:skilledWorkerId")

