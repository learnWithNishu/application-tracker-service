package application.tracker.service.service;

import application.tracker.service.dto.*;
import org.springframework.data.domain.Page;

import java.util.List;

public interface JobApplicationService {

    JobApplicationResponse createApplication(JobApplicationRequest request, Long userId, String token);
    Page<JobApplicationResponse> getAllApplications(Long userId, int page, int size);
    JobApplicationResponse getApplicationById(Long id, Long userId);
    JobApplicationResponse updateStatus(Long id, Long userId, UpdateStatusRequest request) ;
    void deleteApplication(Long id, Long userId);
    ReuseRecommendation checkReuseRecommendation(String newJdText, Long userId);
    void generateAndSaveEmbedding(Long applicationId, String jobDesciption);
    List<StaleApplicationDto> getStaleApplications();
    List<UpcomingInterviewDto> getUpcomingInterviewsApplications();

    ApplicationStatsResponse getApplicationStats(Long userId);

}
