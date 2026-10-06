package application.tracker.service.repository;

import application.tracker.service.entity.JobApplication;
import application.tracker.service.enums.ApplicationStatus;
import application.tracker.service.exceptions.ApplicationNotFoundException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {


    Page<JobApplication> findByUserId(Long userId, Pageable pageable);
    JobApplication findByUserIdAndId(Long userId, Long id) throws ApplicationNotFoundException;
    @Query("Select j from JobApplication j " +
            "Where j.userId = :userId Order by j.appliedAt DESC")
    List<JobApplication> findByUserId(@Param("userId") Long userId);

    List<JobApplication> findByUserIdAndJdEmbeddingIsNotNull(Long userId);
    @Query("SELECT j from JobApplication j Where j.status = :status" +
    " AND j.appliedAt <:cutoffDate")
    List<JobApplication> findStaleApplication(@Param("status") ApplicationStatus status,
                                              @Param("cutoffDate")LocalDateTime cutoffDate);

    @Query("Select j From JobApplication j Where j.status = 'INTERVIEW_SCHEDULED' " +
    " AND j.interviewDate = :tomorrow")
    List<JobApplication> findUpcomingInterviews(@Param("tomorrow")LocalDate tomorrow);

    @Modifying
    @Transactional
    @Query(value = "UPDATE job_applications SET jd_embedding = cast(:embedding as vector) WHERE id = :id", nativeQuery = true)
    void updateJdEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    Long countByUserId(Long userId);

    Long countByUserIdAndStatus(Long userId, ApplicationStatus status);

    @Query("SELECT AVG(j.matchScore) FROM JobApplication j " +
            "WHERE j.userId = :userId AND j.matchScore IS NOT NULL")
    Double findAverageMatchScoreByUserId(@Param("userId") Long userId);

    @Query("SELECT MAX(j.matchScore) FROM JobApplication j " +
            "WHERE j.userId = :userId AND j.matchScore IS NOT NULL")
    Double findHighestMatchScoreByUserId(@Param("userId") Long userId);

    Long countByUserIdAndMatchScoreIsNotNull(Long userId);

    @Query("SELECT COUNT(j) FROM JobApplication j " +
            "WHERE j.userId = :userId " +
            "AND j.appliedAt >= :startOfMonth")
    Long countApplicationsThisMonth(
            @Param("userId") Long userId,
            @Param("startOfMonth") LocalDateTime startOfMonth);

    List<JobApplication> findTop5ByUserIdOrderByAppliedAtDesc(Long userId);

}
