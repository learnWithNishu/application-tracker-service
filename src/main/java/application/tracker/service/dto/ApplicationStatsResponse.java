package application.tracker.service.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApplicationStatsResponse {

    private Long totalApplications;
    private Map<String, Long> applicationsByStatus;
    private Double averageMatchScore;

    private Long totalWithMatchScore;
    private Double highestMatchScore;
    private Long applicationsThisMonth;
}
