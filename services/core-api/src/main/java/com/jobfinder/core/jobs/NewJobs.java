package com.jobfinder.core.jobs;

import java.util.List;

/** The newest of the jobs a saved search found, and how many it found in all. */
public record NewJobs(int total, List<JobCard> jobs) {

    public static final NewJobs NONE = new NewJobs(0, List.of());
}
