package dev.pgcp.cp.jobs;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.cp.common.ApiException;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record JobDetails(JobService.Job job, List<JobService.Event> events) {
    }

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public List<JobService.Job> list(@RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String status, @RequestParam(required = false) String resourceKey) {
        return jobs.list(limit, status, resourceKey);
    }

    @GetMapping("/{id}")
    public JobDetails get(@PathVariable UUID id) {
        return new JobDetails(jobs.get(id).orElseThrow(() -> ApiException.notFound("job")), jobs.events(id));
    }
}
