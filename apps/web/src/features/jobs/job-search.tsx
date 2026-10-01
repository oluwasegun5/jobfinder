"use client";

import { Search, SlidersHorizontal, Sparkles } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMemo, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { FormError } from "@/features/auth/form-parts";
import { problemCode, problemMessage } from "@/features/auth/api-errors";
import { ApiProblem } from "@/features/profile/queries";
import { SaveSearch } from "@/features/notifications/save-search";
import { Field } from "@/features/profile/fields";

import { JobCard } from "./job-card";
import { useJobSearch } from "./queries";
import {
  activeFilterCount,
  CURRENCIES,
  EMPLOYMENT_TYPE_LABELS,
  EMPLOYMENT_TYPES,
  filtersFromSearchParams,
  filtersToQueryString,
  NO_FILTERS,
  POSTED_WITHIN,
  SENIORITIES,
  SENIORITY_LABELS,
  WORK_MODE_LABELS,
  WORK_MODES,
  type JobFilters,
} from "./search-params";

function toggle<T extends string>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

function CheckGroup<T extends string>({
  legend,
  options,
  labels,
  selected,
  onChange,
}: {
  legend: string;
  options: readonly T[];
  labels: Record<T, string>;
  selected: T[];
  onChange: (next: T[]) => void;
}) {
  return (
    <fieldset className="flex min-w-0 flex-col gap-1.5">
      <legend className="text-sm font-medium">{legend}</legend>
      {options.map((option) => (
        <label key={option} className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="size-4 rounded border-input accent-primary focus-visible:ring-3 focus-visible:ring-ring/50"
            checked={selected.includes(option)}
            onChange={() => onChange(toggle(selected, option))}
          />
          {labels[option]}
        </label>
      ))}
    </fieldset>
  );
}

const selectClass =
  "h-9 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30";

/** The filter panel edits a draft; "Apply filters" writes it to the URL, which is the single source of truth. */
function FilterPanel({ filters, onApply }: { filters: JobFilters; onApply: (next: JobFilters) => void }) {
  const [draft, setDraft] = useState(filters);
  const set = <K extends keyof JobFilters>(key: K, value: JobFilters[K]) => setDraft((d) => ({ ...d, [key]: value }));

  return (
    <form
      aria-label="Filters"
      className="grid gap-4 rounded-xl border p-4 sm:grid-cols-2 lg:grid-cols-3"
      onSubmit={(e) => {
        e.preventDefault();
        onApply({ ...draft, minSalary: draft.minSalary.replace(/\D/g, "") });
      }}
    >
      <CheckGroup legend="Work mode" options={WORK_MODES} labels={WORK_MODE_LABELS} selected={draft.workMode} onChange={(v) => set("workMode", v)} />
      <CheckGroup
        legend="Employment type"
        options={EMPLOYMENT_TYPES}
        labels={EMPLOYMENT_TYPE_LABELS}
        selected={draft.employmentType}
        onChange={(v) => set("employmentType", v)}
      />
      <CheckGroup legend="Seniority" options={SENIORITIES} labels={SENIORITY_LABELS} selected={draft.seniority} onChange={(v) => set("seniority", v)} />
      <Field label="City" id="filter-location" value={draft.location} maxLength={200} onChange={(e) => set("location", e.target.value)} />
      <Field
        label="Country code"
        id="filter-country"
        hint="Two letters, such as NG or GB."
        value={draft.country}
        maxLength={2}
        onChange={(e) => set("country", e.target.value.toUpperCase())}
      />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="filter-posted">Posted within</Label>
        <select id="filter-posted" className={selectClass} value={draft.postedWithinDays} onChange={(e) => set("postedWithinDays", e.target.value)}>
          <option value="">Any time</option>
          {POSTED_WITHIN.map((days) => (
            <option key={days} value={String(days)}>
              {days === 1 ? "Last 24 hours" : `Last ${days} days`}
            </option>
          ))}
        </select>
      </div>
      <Field
        label="Minimum yearly salary"
        id="filter-min-salary"
        inputMode="numeric"
        hint="Jobs that state no salary period are left out."
        value={draft.minSalary}
        onChange={(e) => set("minSalary", e.target.value.replace(/\D/g, ""))}
      />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="filter-currency">Salary currency</Label>
        <select id="filter-currency" className={selectClass} value={draft.salaryCurrency} onChange={(e) => set("salaryCurrency", e.target.value)}>
          {CURRENCIES.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-wrap items-end gap-2 sm:col-span-2 lg:col-span-3">
        <Button type="submit">Apply filters</Button>
        <Button
          type="button"
          variant="outline"
          onClick={() => {
            setDraft({ ...NO_FILTERS, q: filters.q, similarTo: filters.similarTo });
            onApply({ ...NO_FILTERS, q: filters.q, similarTo: filters.similarTo });
          }}
        >
          Clear filters
        </Button>
      </div>
    </form>
  );
}

export function JobSearch() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const filters = useMemo(() => filtersFromSearchParams(new URLSearchParams(searchParams.toString())), [searchParams]);
  const [showFilters, setShowFilters] = useState(false);
  const [keyword, setKeyword] = useState(filters.q);
  const search = useJobSearch(filters);

  const go = (next: JobFilters) => {
    const query = filtersToQueryString(next);
    router.push(query ? `${pathname}?${query}` : pathname);
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    go({ ...filters, q: keyword.trim().slice(0, 200), similarTo: "" });
  };

  const jobs = search.data?.pages.flatMap((page) => page.items ?? []) ?? [];
  const count = activeFilterCount(filters);
  const problem = search.error instanceof ApiProblem ? search.error.problem : undefined;
  const errorText = search.error
    ? problemCode(problem) === "search_timeout"
      ? "The search took too long. Try a more specific keyword or a filter."
      : problemMessage(problem, "Could not load jobs. Try again.")
    : undefined;

  return (
    <div className="flex flex-col gap-4">
      {filters.similarTo ? (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border bg-muted/40 p-3 text-sm">
          <Sparkles className="size-4" aria-hidden />
          <span>Showing jobs similar to one you were looking at.</span>
          <Link href={`/jobs/${filters.similarTo}`} className="underline">
            Back to that job
          </Link>
          <Button variant="outline" size="sm" onClick={() => go({ ...filters, similarTo: "" })}>
            Back to search
          </Button>
        </div>
      ) : (
        <form role="search" aria-label="Search jobs" className="flex flex-wrap items-end gap-2" onSubmit={onSubmit}>
          <div className="min-w-[14rem] flex-1">
            <Label htmlFor="job-keyword" className="sr-only">
              Keywords
            </Label>
            <Input
              id="job-keyword"
              type="search"
              placeholder="Job title, company or skill"
              maxLength={200}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <Button type="submit">
            <Search /> Search
          </Button>
        </form>
      )}

      <div>
        <Button variant="outline" size="sm" aria-expanded={showFilters} aria-controls="job-filters" onClick={() => setShowFilters((s) => !s)}>
          <SlidersHorizontal /> Filters{count > 0 ? ` (${count})` : ""}
        </Button>
      </div>
      {!filters.similarTo && <SaveSearch filters={filters} />}
      {showFilters && (
        <div id="job-filters">
          <FilterPanel
            key={filtersToQueryString(filters)}
            filters={filters}
            onApply={(next) => go({ ...next, q: filters.q, similarTo: filters.similarTo })}
          />
        </div>
      )}

      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {search.isPending ? "Loading jobs" : errorText ? "" : jobs.length === 0 ? "No jobs found" : `${jobs.length} job${jobs.length === 1 ? "" : "s"} shown`}
      </div>
      <FormError>{errorText}</FormError>
      {search.isError && (
        <div>
          <Button variant="outline" onClick={() => void search.refetch()}>
            Try again
          </Button>
        </div>
      )}

      {jobs.length > 0 && (
        <ul className="flex flex-col gap-3" aria-label="Job results">
          {jobs.map((job) => (
            <JobCard key={job.id} job={job} />
          ))}
        </ul>
      )}
      {!search.isPending && !search.isError && jobs.length === 0 && (
        <p className="text-sm text-muted-foreground">Nothing matched. Try fewer filters or different keywords.</p>
      )}

      {search.hasNextPage && (
        <div>
          <Button variant="outline" disabled={search.isFetchingNextPage} onClick={() => void search.fetchNextPage()}>
            {search.isFetchingNextPage ? "Loading" : "Load more"}
          </Button>
        </div>
      )}
    </div>
  );
}
