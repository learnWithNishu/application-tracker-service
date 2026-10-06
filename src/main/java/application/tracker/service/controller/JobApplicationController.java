package application.tracker.service.controller;

import application.tracker.service.dto.*;
import application.tracker.service.exceptions.UnauthorizedAccessException;
import application.tracker.service.service.JobApplicationService;
import application.tracker.service.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
@AllArgsConstructor
public class JobApplicationController {

    private JobApplicationService jobApplicationService;
    private JwtService jwtService;
    private HttpServletRequest request;

    @PostMapping("/create-application")
    public ResponseEntity<JobApplicationResponse> createNewApplication(@RequestBody @Valid JobApplicationRequest jobApplicationRequest){
            String token = request.getHeader("Authorization").substring(7);
            Long userId = jwtService.extractUserId(token);
            return ResponseEntity.status(HttpStatus.CREATED).body(jobApplicationService.createApplication(jobApplicationRequest, userId, token));

    }

    @GetMapping("/all-applications")
    public ResponseEntity<Page<JobApplicationResponse>> getAllApplicationWithPage(@RequestParam int page, @RequestParam int size){
       String token = request.getHeader("Authorization").substring(7);
       Long userId = jwtService.extractUserId(token);
        Page<JobApplicationResponse> jobPg = jobApplicationService.getAllApplications(userId,page,size);

        return ResponseEntity.ok(jobPg);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobApplicationResponse> getOneApplication(@PathVariable Long id){
        String token = request.getHeader("Authorization").substring(7);
        Long userId= jwtService.extractUserId(token);
        return ResponseEntity.ok(jobApplicationService.getApplicationById(id,userId));
    }
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateJobStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest updateStatusRequest){
       String token = request.getHeader("Authorization").substring(7);
        Long userId= jwtService.extractUserId(token);
        JobApplicationResponse updated = jobApplicationService.updateStatus(id,userId,updateStatusRequest);
        return ResponseEntity.ok(updated);

    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteApplicationById(@PathVariable Long id) throws UnauthorizedAccessException{
       String token = request.getHeader("Authorization").substring(7);
        Long userId= jwtService.extractUserId(token);
        jobApplicationService.deleteApplication(id,userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/check-reuse")
    public ResponseEntity<ReuseRecommendation> checkReuseResume(@RequestBody @Valid ReuseCheckRequest reuseCheckRequest){
        String token = request.getHeader("Authorization").substring(7);
        Long userId = jwtService.extractUserId(token);
        ReuseRecommendation recommendation = jobApplicationService.checkReuseRecommendation(reuseCheckRequest.getJobDescription(), userId);
        return ResponseEntity.ok(recommendation);
    }

    @GetMapping("/internal/stale")
    public ResponseEntity<List<StaleApplicationDto>>  getStaleApplicationforReminder(){
//        String token = request.getHeader("Authorization").substring(7);
        return ResponseEntity.ok().body(jobApplicationService.getStaleApplications());
    }

    @GetMapping("/internal/upcoming-interviews")
    public ResponseEntity<List<UpcomingInterviewDto>> getUpcomingInterviewApp(){
        //String token = request.getHeader("Authorization").substring(7);
        return ResponseEntity.ok().body(jobApplicationService.getUpcomingInterviewsApplications());
    }

    @GetMapping("/stats/{userId}")
    public ResponseEntity<ApplicationStatsResponse> getApplicationStats(
            @PathVariable Long userId) {
        return ResponseEntity.ok(jobApplicationService.getApplicationStats(userId));
    }
    }


