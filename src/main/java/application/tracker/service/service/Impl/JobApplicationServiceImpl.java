package application.tracker.service.service.Impl;

import application.tracker.service.client.MatchServiceClient;
import application.tracker.service.client.UserServiceClient;
import application.tracker.service.dto.*;
import application.tracker.service.entity.JobApplication;
import application.tracker.service.enums.ApplicationStatus;
import application.tracker.service.events.ApplicationStatusEvent;
import application.tracker.service.exceptions.ApplicationNotFoundException;
import application.tracker.service.exceptions.UnauthorizedAccessException;
import application.tracker.service.repository.JobApplicationRepository;
import application.tracker.service.service.EmbeddingWorkerService;
import application.tracker.service.service.JobApplicationService;
import application.tracker.service.service.KafkaProducerService;
import application.tracker.service.util.EmbeddingUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobApplicationServiceImpl implements JobApplicationService {

    private final JobApplicationRepository jobApplicationRepository;
    private final MatchServiceClient matchServiceClient;
    private final UserServiceClient userServiceClient;
    private final KafkaProducerService kafkaProducerService;

    private final OllamaEmbeddingModel embeddingModel;
    private final EmbeddingWorkerService embeddingWorkerService;
    private final EmbeddingUtils embeddingUtils;
    private final MeterRegistry meterRegistry;
    
    @Value("${app.similarity.threshold}")
    private double scoreThreshold;
    @Value(("${app.notifications.stale-days}"))
    private int staleDays;

    private Counter applicationCreatedCounter;
    private Counter matchScoreSuccessCounter;
    private Counter matchScoreFailureCounter;

    @PostConstruct
    public void initMetrics(){
        applicationCreatedCounter = Counter.builder("job.applications.created")
                .description("Total job applications created")
                .register(meterRegistry);
        matchScoreSuccessCounter = Counter.builder("match.score.success")
                .description("Successful match score calculations")
                .register(meterRegistry);
        matchScoreFailureCounter = Counter.builder("match.score.failure")
                .description("Failed match score calculations (circuit breaker)")
                .register(meterRegistry);
    }

    @CacheEvict(value = "applications", allEntries = true)
    @Transactional
    @Override
    public JobApplicationResponse createApplication(JobApplicationRequest request, Long userId, String token) {
        JobApplication jobApp = new JobApplication();
        jobApp.setJobTitle(request.getJobTitle());
        jobApp.setAppliedAt(LocalDateTime.now());
        jobApp.setCompanyName(request.getCompanyName());
        jobApp.setStatus(ApplicationStatus.APPLIED);
        jobApp.setJobDescription(request.getJobDescription());
        jobApp.setUserId(userId);
        JobApplication savedApp = jobApplicationRepository.save(jobApp);
        applicationCreatedCounter.increment();
        if(request.getResumeId()!= null) {
            savedApp.setResumeId(request.getResumeId());
            MatchScoreResponse response = matchServiceClient.getMatchScore(
                    request.getJobDescription(),
                    request.getResumeId(),
                    token
            );
            if (response != null){
                savedApp.setMatchScore(response.getScore());
                jobApplicationRepository.save(savedApp);
                matchScoreSuccessCounter.increment();
            } else {
                matchScoreFailureCounter.increment();
            }
            embeddingWorkerService.generateAndSaveEmbedding(savedApp.getId(), request.getJobDescription());

        }
        JobApplicationResponse response = new JobApplicationResponse();
        response.setId(savedApp.getId());
        response.setCompanyName(savedApp.getCompanyName());
        response.setAppliedAt(savedApp.getAppliedAt());
        response.setJobTitle(savedApp.getJobTitle());
        response.setUserId(savedApp.getUserId());
        response.setStatus(savedApp.getStatus());
        response.setResumeId(savedApp.getResumeId());
        response.setMatchScore(savedApp.getMatchScore());
        response.setDescription(savedApp.getJobDescription());
        return response;
    }

    @Cacheable(value = "applications", key = "#userId + '-page-' + #page + '-size-' + #size")
    @Override
    public Page<JobApplicationResponse> getAllApplications(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page,size, Sort.by("appliedAt").descending());
        Page<JobApplication> jobAppPage = jobApplicationRepository.findByUserId(userId, pageable);
        Page<JobApplicationResponse> responsePage = jobAppPage.map(jobApp->{
            JobApplicationResponse response = new JobApplicationResponse();
            response.setId(jobApp.getId());
            response.setUserId(jobApp.getUserId());
            response.setCompanyName(jobApp.getCompanyName());
            response.setJobTitle(jobApp.getJobTitle());
            response.setAppliedAt(jobApp.getAppliedAt());
            response.setStatus(jobApp.getStatus());
            response.setMatchScore(jobApp.getMatchScore());
            response.setResumeId(jobApp.getResumeId());
            return response;
        });
        return responsePage;
    }

    @Cacheable(value = "applications",
     key = "'app-' +#id + '-user-' + #userId")
    @Override
    public JobApplicationResponse getApplicationById(Long id, Long userId) {
        JobApplication jobApplication = jobApplicationRepository.findByUserIdAndId(userId,id);
        if(jobApplication == null){
                    throw new ApplicationNotFoundException("Application not found for id: " + id);
        }
        JobApplicationResponse jobResponse = new JobApplicationResponse();
        jobResponse.setStatus(jobApplication.getStatus());
        jobResponse.setDescription(jobApplication.getJobDescription());
        jobResponse.setMatchScore(jobApplication.getMatchScore());
        jobResponse.setJobTitle(jobApplication.getJobTitle());
        jobResponse.setAppliedAt(jobApplication.getAppliedAt());
        jobResponse.setCompanyName(jobApplication.getCompanyName());
        jobResponse.setResumeId(jobApplication.getResumeId());
        jobResponse.setId(id);
        jobResponse.setUserId(userId);
    return jobResponse;
    }

    @Caching(evict = {
            @CacheEvict(value = "applications",
            key = "'app-' + #id + '-user-' + #userId"),
            @CacheEvict(value = "applications", allEntries = true)
    })
    @Transactional
    @Override
    public JobApplicationResponse updateStatus(Long id, Long userId, UpdateStatusRequest request) {
        JobApplication jobApplication = jobApplicationRepository.findByUserIdAndId(userId,id);
        if(jobApplication == null){
                   throw new ApplicationNotFoundException("Application not found for id: " + id);
        }
        jobApplication.setStatus(request.getStatus());
        if(ApplicationStatus.INTERVIEW_SCHEDULED.equals(request.getStatus())){
            jobApplication.setInterviewDate(request.getInterviewDate());
        } else{
            jobApplication.setInterviewDate(null);
        }
        jobApplication.setLastStatusUpdatedAt(LocalDateTime.now());
        JobApplication savedApp = jobApplicationRepository.save(jobApplication);
        UserDetailsDto userDetailsDto = userServiceClient.getUserDetails(userId);
        ApplicationStatusEvent event = new ApplicationStatusEvent(
            savedApp.getId(),
            savedApp.getUserId(),
            savedApp.getCompanyName(),
            savedApp.getJobTitle(),
            savedApp.getStatus(),
            userDetailsDto != null ? userDetailsDto.getEmail() : "",
            userDetailsDto != null ? userDetailsDto.getName(): ""
        );
        kafkaProducerService.publishStatusChangeEvent(event);

        JobApplicationResponse jobAppResponse = new JobApplicationResponse();
        jobAppResponse.setStatus(savedApp.getStatus());
        jobAppResponse.setId(savedApp.getId());
        jobAppResponse.setCompanyName(savedApp.getCompanyName());
        jobAppResponse.setJobTitle(savedApp.getJobTitle());
        jobAppResponse.setUserId(savedApp.getUserId());
        jobAppResponse.setAppliedAt(savedApp.getAppliedAt());
        jobAppResponse.setMatchScore(savedApp.getMatchScore());
        jobAppResponse.setResumeId(savedApp.getResumeId());
        jobAppResponse.setDescription(savedApp.getJobDescription());
        if(ApplicationStatus.INTERVIEW_SCHEDULED.equals(savedApp.getStatus())){
            jobAppResponse.setPrepLink("/api/interview-prep/generate");
           jobAppResponse.setMessage("Interview scheduled! Click here to generate your interview prep plan.");
        } else{
            jobAppResponse.setMessage("Application status updated to " + savedApp.getStatus());
        }

        return jobAppResponse;
    }



    @CacheEvict(value = "applications", allEntries = true)
    @Transactional
    @Override
    public void deleteApplication(Long id, Long userId) {
        JobApplication jobApp = jobApplicationRepository.findById(id).orElseThrow(()-> new ApplicationNotFoundException("Application not found!"));

        if(!jobApp.getUserId().equals(userId)){
            throw new UnauthorizedAccessException("You do not have permission to delete this application. ");
        }else {
            jobApplicationRepository.deleteById(id);
        }


    }

    @Override
    public ReuseRecommendation checkReuseRecommendation(String newJdText, Long userId) {
        float[] jdEmbedding = embeddingModel.embed(newJdText);

        List<JobApplication> jobWithEmbeddings = jobApplicationRepository.findByUserIdAndJdEmbeddingIsNotNull(userId);

        double highestScore = 0.0;
        JobApplication bestMatch = null;

        for(JobApplication job: jobWithEmbeddings){
            double similarityScore = embeddingUtils.cosineSimilarity(jdEmbedding, job.getJdEmbedding()) * 100;
            if(similarityScore > highestScore){
                highestScore = similarityScore;
                bestMatch = job;
            }
        }

        ReuseRecommendation recommendation = new ReuseRecommendation();
        if(bestMatch != null && highestScore > scoreThreshold){
            recommendation.setShouldReuse(true);
            recommendation.setSimilarityScore(highestScore);
            recommendation.setRecommendedResumeId(bestMatch.getResumeId());
            recommendation.setMatchedJobTitle(bestMatch.getJobTitle());
            recommendation.setMatchedCompanyName(bestMatch.getCompanyName());
            recommendation.setMessage("Your resume for " + bestMatch.getJobTitle()
                    + " at " + bestMatch.getCompanyName()
                    + " is " + Math.round(highestScore) + "% similar — consider reusing it");
        } else {
            recommendation.setShouldReuse(false);
            recommendation.setSimilarityScore(highestScore);
            recommendation.setMessage("No similar past applications found — a fresh resume is recommended");
        }
        return recommendation;
    }

    @Override
    public void generateAndSaveEmbedding(Long applicationId, String jobDescription) {
        embeddingWorkerService.generateAndSaveEmbedding(applicationId, jobDescription);
    }

    @Override
    public List<StaleApplicationDto> getStaleApplications(){
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(staleDays);
        List<JobApplication> staleApps = jobApplicationRepository.findStaleApplication(ApplicationStatus.APPLIED,cutoffDate);
        return staleApps.stream().map(app -> {
                    long daysSincedApplied = ChronoUnit.DAYS.between(app.getAppliedAt(), LocalDateTime.now());
                    UserDetailsDto user = userServiceClient.getUserDetails(app.getUserId());

                    return  new StaleApplicationDto(
                            app.getId(),
                            app.getUserId(),
                            user != null ? user.getName() : "",
                            user != null ? user.getEmail() : "",
                            app.getCompanyName(),
                            app.getJobTitle(),
                            (int) daysSincedApplied
                    );
                }).toList();
    }

    @Override
    public List<UpcomingInterviewDto> getUpcomingInterviewsApplications(){
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<JobApplication> interviewApps = jobApplicationRepository.findUpcomingInterviews(tomorrow);
         return  interviewApps.stream().map( interview -> {
             UserDetailsDto user = userServiceClient.getUserDetails(interview.getUserId());
              return new UpcomingInterviewDto(
                      interview.getId(),
                      interview.getUserId(),
                      user != null ? user.getName() : "",
                      user != null ? user.getEmail()  : "",
                      interview.getCompanyName(),
                      interview.getJobTitle()
              );

         }).toList();
    }

    @Override
    public ApplicationStatsResponse getApplicationStats(Long userId) {

        Long total = jobApplicationRepository.countByUserId(userId);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            Long count = jobApplicationRepository
                    .countByUserIdAndStatus(userId, status);
            byStatus.put(status.name(), count);
        }

        Double avgScore = jobApplicationRepository
                .findAverageMatchScoreByUserId(userId);
        Double highestScore = jobApplicationRepository
                .findHighestMatchScoreByUserId(userId);

        Long withScore = jobApplicationRepository
                .countByUserIdAndMatchScoreIsNotNull(userId);

        LocalDateTime startOfMonth = LocalDateTime.now()
                .withDayOfMonth(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0);
        Long thisMonth = jobApplicationRepository
                .countApplicationsThisMonth(userId, startOfMonth);

        if (avgScore != null) {
            avgScore = Math.round(avgScore * 10.0) / 10.0;
        }
        if (highestScore != null) {
            highestScore = Math.round(highestScore * 10.0) / 10.0;
        }

        return new ApplicationStatsResponse(
                total,
                byStatus,
                avgScore,
                withScore,
                highestScore,
                thisMonth
        );
    }
}
