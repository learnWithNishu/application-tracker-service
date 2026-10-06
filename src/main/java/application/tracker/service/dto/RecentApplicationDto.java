package application.tracker.service.dto;

import application.tracker.service.enums.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecentApplicationDto {

    private Long id;
    private String companyName;
    private String jobTitle;
    private ApplicationStatus status;
    private LocalDateTime appliedAt;
    private Double matchScore;

}
