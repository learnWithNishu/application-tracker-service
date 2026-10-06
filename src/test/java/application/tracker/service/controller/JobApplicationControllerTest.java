package application.tracker.service.controller;

import application.tracker.service.dto.ApplicationStatsResponse;
import application.tracker.service.service.JobApplicationService;
import application.tracker.service.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class JobApplicationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private JobApplicationService jobApplicationService;

    @Mock
    private JwtService jwtService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private JobApplicationController jobApplicationController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(jobApplicationController).build();
    }

    @Test
    @DisplayName("GET /api/applications/stats/{userId} - should return 200 OK and valid stats response")
    void getApplicationStats_ShouldReturn200AndStatsPayload() throws Exception {
        // Arrange
        Long userId = 1L;
        Map statusCounts = Map.of(
                "APPLIED", 4L,
                "INTERVIEW_SCHEDULED", 2L,
                "REJECTED", 1L,
                "OFFER_RECEIVED", 0L
        );

        ApplicationStatsResponse mockResponse = new ApplicationStatsResponse(
                7L,
                statusCounts,
                81.5,
                6L,
                95.0,
                3L
        );

        when(jobApplicationService.getApplicationStats(userId)).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(get("/api/applications/stats/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalApplications").value(7))
                .andExpect(jsonPath("$.averageMatchScore").value(81.5))
                .andExpect(jsonPath("$.highestMatchScore").value(95.0))
                .andExpect(jsonPath("$.totalWithMatchScore").value(6))
                .andExpect(jsonPath("$.applicationsThisMonth").value(3))
                .andExpect(jsonPath("$.applicationsByStatus.APPLIED").value(4))
                .andExpect(jsonPath("$.applicationsByStatus.INTERVIEW_SCHEDULED").value(2))
                .andExpect(jsonPath("$.applicationsByStatus.REJECTED").value(1))
                .andExpect(jsonPath("$.applicationsByStatus.OFFER_RECEIVED").value(0));

        verify(jobApplicationService, times(1)).getApplicationStats(userId);
    }

    @Test
    @DisplayName("GET /api/applications/stats/{userId} - should return 200 OK when user has empty stats")
    void getApplicationStats_WhenNoApplications_ShouldReturn200WithZerosAndNulls() throws Exception {
        // Arrange
        Long userId = 2L;
        Map emptyStatuses = Map.of(
                "APPLIED", 0L,
                "INTERVIEW_SCHEDULED", 0L,
                "REJECTED", 0L,
                "OFFER_RECEIVED", 0L
        );

        ApplicationStatsResponse emptyResponse = new ApplicationStatsResponse(
                0L,
                emptyStatuses,
                null,
                0L,
                null,
                0L
        );

        when(jobApplicationService.getApplicationStats(userId)).thenReturn(emptyResponse);

        // Act & Assert
        mockMvc.perform(get("/api/applications/stats/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplications").value(0))
                .andExpect(jsonPath("$.averageMatchScore").doesNotExist())
                .andExpect(jsonPath("$.highestMatchScore").doesNotExist())
                .andExpect(jsonPath("$.totalWithMatchScore").value(0))
                .andExpect(jsonPath("$.applicationsThisMonth").value(0))
                .andExpect(jsonPath("$.applicationsByStatus.APPLIED").value(0));

        verify(jobApplicationService, times(1)).getApplicationStats(userId);
    }
}