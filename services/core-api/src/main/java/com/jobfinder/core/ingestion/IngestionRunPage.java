package com.jobfinder.core.ingestion;

import java.util.List;

/** One page of the run history. */
public record IngestionRunPage(List<IngestionRunView> items, int page, int size, long totalElements, int totalPages) {
}
