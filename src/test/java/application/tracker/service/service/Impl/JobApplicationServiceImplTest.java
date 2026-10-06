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
import application.tracker.service.service.KafkaProducerService;
import application.tracker.service.util.EmbeddingUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class JobApplicationServiceImplTest {

    @Mock
    private JobApplicationRepository jobApplicationRepository;
    @Mock
    private MatchServiceClient matchServiceClient;
    @Mock
    private UserServiceClient userServiceClient;
    @Mock
    private KafkaProducerService kafkaProducerService;
    @Mock
    private OllamaEmbeddingModel embeddingModel;
    @Mock
    private EmbeddingWorkerService embeddingWorkerService;
    @Mock
    private EmbeddingUtils embeddingUtils;

    @InjectMocks
    private JobApplicationServiceImpl jobApplicationService;

    private JobApplication testApplication;
    private JobApplicationRequest testRequest;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jobApplicationService, "scoreThreshold", 75.0);

        // Initialize and inject all micrometer counters to prevent NullPointerException
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter applicationCounter = registry.counter("job.applications.created");
        Counter successCounter = registry.counter("match.score.success");
        Counter failureCounter = registry.counter("match.score.failure");

        ReflectionTestUtils.setField(jobApplicationService, "applicationCreatedCounter", applicationCounter);
        ReflectionTestUtils.setField(jobApplicationService, "matchScoreSuccessCounter", successCounter);
        ReflectionTestUtils.setField(jobApplicationService, "matchScoreFailureCounter", failureCounter);

        testApplication = new JobApplication();
        testApplication.setId(1L);
        testApplication.setUserId(5L);
        testApplication.setCompanyName("Google");
        testApplication.setJobTitle("Backend Developer");
        testApplication.setJobDescription("Java Spring Boot microservices developer");
        testApplication.setStatus(ApplicationStatus.APPLIED);
        testApplication.setAppliedAt(LocalDateTime.now());

        testRequest = new JobApplicationRequest();
        testRequest.setCompanyName("Google");
        testRequest.setJobTitle("Backend Developer");
        testRequest.setJobDescription("Java Spring Boot microservices developer");
    }
    @Test
    @DisplayName("Create Application without resumeId never calls Match Service")
    void createApplication_Success_WithoutResumeId() {
        when(jobApplicationRepository.save(any(JobApplication.class)))
                .thenReturn(testApplication);

        JobApplicationResponse response = jobApplicationService.createApplication(
                testRequest, 5L, "Bearer token");

        assertNotNull(response);
        assertEquals("Google", response.getCompanyName());
        assertEquals(ApplicationStatus.APPLIED, response.getStatus());

        verify(matchServiceClient, never()).getMatchScore(anyString(), anyLong(), anyString());
        verify(embeddingWorkerService, never()).generateAndSaveEmbedding(anyLong(), anyString());
    }

    @Test
    @DisplayName("Create application with resumeId calls Match Service")
    void createApplication_CallsMatchService_WhenResumeIdProvided() {
        testRequest.setResumeId(1L);
        MatchScoreResponse mockScore = new MatchScoreResponse(
                87.5, "Strong Java Match", List.of("Redis", "Docker")
        );

        when(jobApplicationRepository.save(any(JobApplication.class)))
                .thenReturn(testApplication);
        when(matchServiceClient.getMatchScore(
                "Java Spring Boot microservices developer", 1L, "Bearer token"))
                .thenReturn(mockScore);

        jobApplicationService.createApplication(testRequest, 5L, "Bearer token");

        verify(matchServiceClient, times(1))
                .getMatchScore("Java Spring Boot microservices developer", 1L, "Bearer token");
        verify(embeddingWorkerService, times(1))
                .generateAndSaveEmbedding(anyLong(), anyString());
    }

    @Test
    @DisplayName("Create application succeeds even when Match Service returns null")
    void createApplication_Succeeds_WhenMatchServiceReturnsNull() {
        testRequest.setResumeId(1L);
        when(jobApplicationRepository.save(any(JobApplication.class)))
                .thenReturn(testApplication);
        when(matchServiceClient.getMatchScore(anyString(), anyLong(), anyString()))
                .thenReturn(null);

        JobApplicationResponse response = jobApplicationService.createApplication(
                testRequest, 5L, "Bearer token");

        assertNotNull(response);
        assertNull(response.getMatchScore());
    }


    @Test
    @DisplayName("getAllApplications returns paginated results for user")
    void getAllApplications_ReturnsPaginatedResults() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("appliedAt").descending());
        Page mockPage = new PageImpl<>(List.of(testApplication), pageable, 1);

        when(jobApplicationRepository.findByUserId(eq(5L), any(Pageable.class)))
                .thenReturn(mockPage);

        Page result = jobApplicationService
                .getAllApplications(5L, 0, 10);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getTotalPages());
    }

    @Test
    @DisplayName("updateStatus successfully changes status and publishes kafka event")
    void updateStatus_Success_AndPublishesKafkaEvent() {
        UpdateStatusRequest updateRequest = new UpdateStatusRequest();
        updateRequest.setStatus(ApplicationStatus.INTERVIEW_SCHEDULED);
        updateRequest.setInterviewDate(LocalDate.now().plusDays(2));
        UserDetailsDto mockUser = new UserDetailsDto();
        mockUser.setName("nk");
        mockUser.setEmail("nk@test.com");

        when(jobApplicationRepository.findByUserIdAndId(5L, 1L))
                .thenReturn(testApplication);
        when(jobApplicationRepository.save(any(JobApplication.class)))
                .thenReturn(testApplication);
        when(userServiceClient.getUserDetails(5L)).thenReturn(mockUser);

        JobApplicationResponse response = jobApplicationService.updateStatus(1L, 5L, updateRequest);

        assertNotNull(response);
        verify(kafkaProducerService, times(1))
                .publishStatusChangeEvent(any(ApplicationStatusEvent.class));
    }

    @Test
    @DisplayName("updateStatus throws ApplicationNotFoundException when not found")
    void updateStatus_ThrowsException_WhenApplicationNotFound() {
        when(jobApplicationRepository.findByUserIdAndId(5L, 999L))
                .thenReturn(null);
        UpdateStatusRequest updateRequest = new UpdateStatusRequest();
        updateRequest.setStatus(ApplicationStatus.REJECTED);

        assertThrows(ApplicationNotFoundException.class,
                () -> jobApplicationService.updateStatus(999L, 5L, updateRequest));
        verify(kafkaProducerService, never())
                .publishStatusChangeEvent(any());
    }

    @Test
    @DisplayName("deleteApplication succeeds when user owns application")
    void deleteApplication_Success_WhenUserOwnsApplication() {
        when(jobApplicationRepository.findById(1L))
                .thenReturn(Optional.of(testApplication));

        jobApplicationService.deleteApplication(1L, 5L);

        verify(jobApplicationRepository, times(1)).deleteById(1L);
    }

    @Test
    @DisplayName("deleteApplication throws UnauthorizedAccessException when wrong user")
    void deleteApplication_ThrowsException_WhenWrongUser() {
        when(jobApplicationRepository.findById(1L))
                .thenReturn(Optional.of(testApplication));

        assertThrows(UnauthorizedAccessException.class,
                () -> jobApplicationService.deleteApplication(1L, 99L));

        verify(jobApplicationRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("deleteApplication throws ApplicationNotFoundException when not found")
    void deleteApplication_ThrowsException_WhenApplicationNotFound() {
        when(jobApplicationRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(ApplicationNotFoundException.class,
                () -> jobApplicationService.deleteApplication(999L, 5L));
    }

    @Test
    void getApplicationStats_ShouldCalculateAndReturnCorrectStats() {
        // Arrange
        Long userId = 1L;
        LocalDateTime now = LocalDateTime.now();

        when(jobApplicationRepository.countByUserId(userId)).thenReturn(10L);
        when(jobApplicationRepository.countByUserIdAndStatus(userId, ApplicationStatus.APPLIED)).thenReturn(5L);
        when(jobApplicationRepository.countByUserIdAndStatus(userId, ApplicationStatus.INTERVIEW_SCHEDULED)).thenReturn(2L);
        when(jobApplicationRepository.countByUserIdAndStatus(userId, ApplicationStatus.REJECTED)).thenReturn(2L);
        when(jobApplicationRepository.countByUserIdAndStatus(userId, ApplicationStatus.OFFER_RECEIVED)).thenReturn(1L);

        when(jobApplicationRepository.findAverageMatchScoreByUserId(userId)).thenReturn(78.456);
        when(jobApplicationRepository.findHighestMatchScoreByUserId(userId)).thenReturn(92.34);
        when(jobApplicationRepository.countByUserIdAndMatchScoreIsNotNull(userId)).thenReturn(8L);
        when(jobApplicationRepository.countApplicationsThisMonth(eq(userId), any(LocalDateTime.class))).thenReturn(4L);

        // Act
        ApplicationStatsResponse response = jobApplicationService.getApplicationStats(userId);

        // Assert
        assertNotNull(response);
        assertEquals(10L, response.getTotalApplications());
        assertEquals(8L, response.getTotalWithMatchScore());
        assertEquals(4L, response.getApplicationsThisMonth());

        // Check rounding (1 decimal place)
        assertEquals(78.5, response.getAverageMatchScore());
        assertEquals(92.3, response.getHighestMatchScore());

        // Check status map
        assertNotNull(response.getApplicationsByStatus());
        assertEquals(5L, response.getApplicationsByStatus().get("APPLIED"));
        assertEquals(2L, response.getApplicationsByStatus().get("INTERVIEW_SCHEDULED"));
        assertEquals(2L, response.getApplicationsByStatus().get("REJECTED"));
        assertEquals(1L, response.getApplicationsByStatus().get("OFFER_RECEIVED"));

        verify(jobApplicationRepository, times(1)).countByUserId(userId);
        verify(jobApplicationRepository, times(1)).findAverageMatchScoreByUserId(userId);
        verify(jobApplicationRepository, times(1)).findHighestMatchScoreByUserId(userId);
    }

    @Test
    void getApplicationStats_WhenNoScores_ShouldHandleNullsGracefully() {
        Long userId = 2L;

        when(jobApplicationRepository.countByUserId(userId)).thenReturn(0L);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            when(jobApplicationRepository.countByUserIdAndStatus(userId, status)).thenReturn(0L);
        }
        when(jobApplicationRepository.findAverageMatchScoreByUserId(userId)).thenReturn(null);
        when(jobApplicationRepository.findHighestMatchScoreByUserId(userId)).thenReturn(null);
        when(jobApplicationRepository.countByUserIdAndMatchScoreIsNotNull(userId)).thenReturn(0L);
        when(jobApplicationRepository.countApplicationsThisMonth(eq(userId), any(LocalDateTime.class))).thenReturn(0L);

        ApplicationStatsResponse response = jobApplicationService.getApplicationStats(userId);

        assertNotNull(response);
        assertEquals(0L, response.getTotalApplications());
        assertNull(response.getAverageMatchScore());
        assertNull(response.getHighestMatchScore());
    }

}